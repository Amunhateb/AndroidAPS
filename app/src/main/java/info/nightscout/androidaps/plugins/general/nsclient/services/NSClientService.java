package info.nightscout.androidaps.plugins.general.nsclient.services;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.work.OneTimeWorkRequest;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.j256.ormlite.dao.CloseableIterator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.android.DaggerService;
import dagger.android.HasAndroidInjector;
import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.database.AppRepository;
import info.nightscout.androidaps.database.entities.DeviceStatus;
import info.nightscout.androidaps.database.transactions.UpdateNsIdBolusCalculatorResultTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdBolusTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdCarbsTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdDeviceStatusTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdExtendedBolusTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdFoodTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdGlucoseValueTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdTemporaryBasalTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdTemporaryTargetTransaction;
import info.nightscout.androidaps.database.transactions.UpdateNsIdTherapyEventTransaction;
import info.nightscout.androidaps.db.DbRequest;
import info.nightscout.androidaps.events.EventAppExit;
import info.nightscout.androidaps.events.EventConfigBuilderChange;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.interfaces.DataSyncSelector;
import info.nightscout.androidaps.interfaces.DatabaseHelperInterface;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.UploadQueueInterface;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.bus.RxBusWrapper;
import info.nightscout.androidaps.plugins.general.food.FoodPlugin;
import info.nightscout.androidaps.plugins.general.nsclient.NSClientAddUpdateWorker;
import info.nightscout.androidaps.plugins.general.nsclient.NSClientMbgWorker;
import info.nightscout.androidaps.plugins.general.nsclient.NSClientPlugin;
import info.nightscout.androidaps.plugins.general.nsclient.NSClientRemoveWorker;
import info.nightscout.androidaps.plugins.general.nsclient.acks.NSAddAck;
import info.nightscout.androidaps.plugins.general.nsclient.acks.NSAuthAck;
import info.nightscout.androidaps.plugins.general.nsclient.acks.NSUpdateAck;
import info.nightscout.androidaps.plugins.general.nsclient.data.AlarmAck;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSAlarm;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSDeviceStatus;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSSettingsStatus;
import info.nightscout.androidaps.plugins.general.nsclient.events.EventNSClientNewLog;
import info.nightscout.androidaps.plugins.general.nsclient.events.EventNSClientRestart;
import info.nightscout.androidaps.plugins.general.nsclient.events.EventNSClientStatus;
import info.nightscout.androidaps.plugins.general.nsclient.events.EventNSClientUpdateGUI;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.general.overview.notifications.NotificationWithAction;
import info.nightscout.androidaps.plugins.profile.ns.NSProfilePlugin;
import info.nightscout.androidaps.plugins.source.NSClientSourcePlugin;
import info.nightscout.androidaps.receivers.DataWorker;
import info.nightscout.androidaps.services.Intents;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.JsonHelper;
import info.nightscout.androidaps.utils.T;
import info.nightscout.androidaps.utils.buildHelper.BuildHelper;
import info.nightscout.androidaps.utils.resources.ResourceHelper;
import info.nightscout.androidaps.utils.rx.AapsSchedulers;
import info.nightscout.androidaps.utils.sharedPreferences.SP;
import io.reactivex.disposables.CompositeDisposable;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class NSClientService extends DaggerService {
    @Inject HasAndroidInjector injector;
    @Inject AAPSLogger aapsLogger;
    @Inject AapsSchedulers aapsSchedulers;
    @Inject NSSettingsStatus nsSettingsStatus;
    @Inject NSDeviceStatus nsDeviceStatus;
    @Inject DatabaseHelperInterface databaseHelper;
    @Inject RxBusWrapper rxBus;
    @Inject ResourceHelper resourceHelper;
    @Inject SP sp;
    @Inject FabricPrivacy fabricPrivacy;
    @Inject NSClientPlugin nsClientPlugin;
    @Inject BuildHelper buildHelper;
    @Inject Config config;
    @Inject DateUtil dateUtil;
    @Inject UploadQueueInterface uploadQueue;
    @Inject DataWorker dataWorker;
    @Inject DataSyncSelector dataSyncSelector;
    @Inject AppRepository repository;

    private final CompositeDisposable disposable = new CompositeDisposable();

    static public PowerManager.WakeLock mWakeLock;
    private final IBinder mBinder = new NSClientService.LocalBinder();

    static public Handler handler;

    public static Socket mSocket;
    public static boolean isConnected = false;
    public static boolean hasWriteAuth = false;
    private static Integer dataCounter = 0;
    private static Integer connectCounter = 0;


    private boolean nsEnabled = false;
    static public String nsURL = "";
    private String nsAPISecret = "";
    private String nsDevice = "";
    private final Integer nsHours = 48;

    public long lastResendTime = 0;
    public long lastAckTime = 0;

    public long latestDateInReceivedData = 0;

    private String nsAPIhashCode = "";

    private final ArrayList<Long> reconnections = new ArrayList<>();
    private final int WATCHDOG_INTERVAL_MINUTES = 2;
    private final int WATCHDOG_RECONNECT_IN = 15;
    private final int WATCHDOG_MAX_CONNECTIONS = 5;

    public NSClientService() {
        super();
        if (handler == null) {
            HandlerThread handlerThread = new HandlerThread(NSClientService.class.getSimpleName() + "Handler");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:NSClientService");
        mWakeLock.acquire();

        initialize();

        disposable.add(rxBus
                .toObservable(EventConfigBuilderChange.class)
                .observeOn(aapsSchedulers.getIo())
                .subscribe(event -> {
                    if (nsEnabled != nsClientPlugin.isEnabled(PluginType.GENERAL)) {
                        latestDateInReceivedData = 0;
                        destroy();
                        initialize();
                    }
                }, fabricPrivacy::logException)
        );
        disposable.add(rxBus
                .toObservable(EventPreferenceChange.class)
                .observeOn(aapsSchedulers.getIo())
                .subscribe(event -> {
                    if (event.isChanged(resourceHelper, R.string.key_nsclientinternal_url) ||
                            event.isChanged(resourceHelper, R.string.key_nsclientinternal_api_secret) ||
                            event.isChanged(resourceHelper, R.string.key_nsclientinternal_paused)
                    ) {
                        latestDateInReceivedData = 0;
                        destroy();
                        initialize();
                    }
                }, fabricPrivacy::logException)
        );
        disposable.add(rxBus
                .toObservable(EventAppExit.class)
                .observeOn(aapsSchedulers.getIo())
                .subscribe(event -> {
                    aapsLogger.debug(LTag.NSCLIENT, "EventAppExit received");
                    destroy();
                    stopSelf();
                }, fabricPrivacy::logException)
        );
        disposable.add(rxBus
                .toObservable(EventNSClientRestart.class)
                .observeOn(aapsSchedulers.getIo())
                .subscribe(event -> {
                    latestDateInReceivedData = 0;
                    restart();
                }, fabricPrivacy::logException)
        );
        disposable.add(rxBus
                .toObservable(NSAuthAck.class)
                .observeOn(aapsSchedulers.getIo())
                .subscribe(this::processAuthAck, fabricPrivacy::logException)
        );
        disposable.add(rxBus
                .toObservable(NSUpdateAck.class)
                .observeOn(aapsSchedulers.getIo())
                .subscribe(this::processUpdateAck, fabricPrivacy::logException)
        );
        disposable.add(rxBus
                .toObservable(NSAddAck.class)
                .observeOn(aapsSchedulers.getIo())
                .subscribe(this::processAddAck, fabricPrivacy::logException)
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposable.clear();
        if (mWakeLock.isHeld()) mWakeLock.release();
    }

    public void processAddAck(NSAddAck ack) {
        lastAckTime = dateUtil._now();
        // new room way
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairTemporaryTarget) {
            DataSyncSelector.PairTemporaryTarget pair = (DataSyncSelector.PairTemporaryTarget) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdTemporaryTargetTransaction((pair.getValue())))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of temporary target " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of temporary target failed")
                    ));
            dataSyncSelector.confirmLastTempTargetsIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked TemporaryTarget " + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedTempTargetsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairGlucoseValue) {
            DataSyncSelector.PairGlucoseValue pair = (DataSyncSelector.PairGlucoseValue) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdGlucoseValueTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of glucose value " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of glucose value failed", error)
                    ));
            dataSyncSelector.confirmLastGlucoseValueIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked GlucoseValue " + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedGlucoseValuesCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairFood) {
            DataSyncSelector.PairFood pair = (DataSyncSelector.PairFood) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdFoodTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of food " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of food failed", error)
                    ));
            dataSyncSelector.confirmLastFoodIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked Food " + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedFoodsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairTherapyEvent) {
            DataSyncSelector.PairTherapyEvent pair = (DataSyncSelector.PairTherapyEvent) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdTherapyEventTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of therapy event " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of therapy event failed", error)
                    ));
            dataSyncSelector.confirmLastTherapyEventIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked TherapyEvent " + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedTherapyEventsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairBolus) {
            DataSyncSelector.PairBolus pair = (DataSyncSelector.PairBolus) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdBolusTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of bolus " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of bolus failed", error)
                    ));
            dataSyncSelector.confirmLastBolusIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked Bolus " + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedBolusesCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairCarbs) {
            DataSyncSelector.PairCarbs pair = (DataSyncSelector.PairCarbs) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdCarbsTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of carbs " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of carbs failed", error)
                    ));
            dataSyncSelector.confirmLastCarbsIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked Carbs" + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedCarbsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairBolusCalculatorResult) {
            DataSyncSelector.PairBolusCalculatorResult pair = (DataSyncSelector.PairBolusCalculatorResult) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdBolusCalculatorResultTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of BolusCalculatorResult " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of BolusCalculatorResult failed", error)
                    ));
            dataSyncSelector.confirmLastBolusCalculatorResultsIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked BolusCalculatorResult" + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedBolusCalculatorResultsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairTemporaryBasal) {
            DataSyncSelector.PairTemporaryBasal pair = (DataSyncSelector.PairTemporaryBasal) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdTemporaryBasalTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of TemporaryBasal " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of TemporaryBasal failed", error)
                    ));
            dataSyncSelector.confirmLastTemporaryBasalIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked TemporaryBasal" + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedTemporaryBasalsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairExtendedBolus) {
            DataSyncSelector.PairExtendedBolus pair = (DataSyncSelector.PairExtendedBolus) ack.getOriginalObject();
            pair.getValue().getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdExtendedBolusTransaction(pair.getValue()))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of ExtendedBolus " + pair.getValue()),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of ExtendedBolus failed", error)
                    ));
            dataSyncSelector.confirmLastExtendedBolusIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked ExtendedBolus" + pair.getValue().getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedTemporaryBasalsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DeviceStatus) {
            DeviceStatus deviceStatus = (DeviceStatus) ack.getOriginalObject();
            deviceStatus.getInterfaceIDs().setNightscoutId(ack.getId());

            disposable.add(repository.runTransactionForResult(new UpdateNsIdDeviceStatusTransaction(deviceStatus))
                    .observeOn(aapsSchedulers.getIo())
                    .subscribe(
                            result -> aapsLogger.debug(LTag.DATABASE, "Updated ns id of DeviceStatus " + deviceStatus),
                            error -> aapsLogger.error(LTag.DATABASE, "Updated ns id of DeviceStatus failed", error)
                    ));
            dataSyncSelector.confirmLastDeviceStatusIdIfGreater(deviceStatus.getId());
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked DeviceStatus" + deviceStatus.getInterfaceIDs().getNightscoutId()));
            // Send new if waiting
            dataSyncSelector.processChangedBolusCalculatorResultsCompat();
            return;
        }
        // old way
        if (ack.nsClientID != null) {
            uploadQueue.removeByNsClientIdIfExists(ack.json);
            rxBus.send(new EventNSClientNewLog("DBADD", "Acked " + ack.nsClientID));
        } else {
            rxBus.send(new EventNSClientNewLog("ERROR", "DBADD Unknown response"));
        }
    }

    public void processUpdateAck(NSUpdateAck ack) {
        lastAckTime = dateUtil._now();
        // new room way
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairTemporaryTarget) {
            DataSyncSelector.PairTemporaryTarget pair = (DataSyncSelector.PairTemporaryTarget) ack.getOriginalObject();
            dataSyncSelector.confirmLastTempTargetsIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked TemporaryTarget" + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedTempTargetsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairGlucoseValue) {
            DataSyncSelector.PairGlucoseValue pair = (DataSyncSelector.PairGlucoseValue) ack.getOriginalObject();
            dataSyncSelector.confirmLastGlucoseValueIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked GlucoseValue " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedGlucoseValuesCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairFood) {
            DataSyncSelector.PairFood pair = (DataSyncSelector.PairFood) ack.getOriginalObject();
            dataSyncSelector.confirmLastFoodIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked Food " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedFoodsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairTherapyEvent) {
            DataSyncSelector.PairTherapyEvent pair = (DataSyncSelector.PairTherapyEvent) ack.getOriginalObject();
            dataSyncSelector.confirmLastTherapyEventIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked TherapyEvent " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedTherapyEventsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairBolus) {
            DataSyncSelector.PairBolus pair = (DataSyncSelector.PairBolus) ack.getOriginalObject();
            dataSyncSelector.confirmLastBolusIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked Bolus " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedBolusesCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairCarbs) {
            DataSyncSelector.PairCarbs pair = (DataSyncSelector.PairCarbs) ack.getOriginalObject();
            dataSyncSelector.confirmLastCarbsIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked Carbs " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedCarbsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairBolusCalculatorResult) {
            DataSyncSelector.PairBolusCalculatorResult pair = (DataSyncSelector.PairBolusCalculatorResult) ack.getOriginalObject();
            dataSyncSelector.confirmLastBolusCalculatorResultsIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked BolusCalculatorResult " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedBolusCalculatorResultsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairTemporaryBasal) {
            DataSyncSelector.PairTemporaryBasal pair = (DataSyncSelector.PairTemporaryBasal) ack.getOriginalObject();
            dataSyncSelector.confirmLastTemporaryBasalIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked TemporaryBasal " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedTemporaryBasalsCompat();
            return;
        }
        if (ack.getOriginalObject() instanceof DataSyncSelector.PairExtendedBolus) {
            DataSyncSelector.PairExtendedBolus pair = (DataSyncSelector.PairExtendedBolus) ack.getOriginalObject();
            dataSyncSelector.confirmLastExtendedBolusIdIfGreater(pair.getUpdateRecordId());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked ExtendedBolus " + ack.get_id()));
            // Send new if waiting
            dataSyncSelector.processChangedExtendedBolusesCompat();
            return;
        }
        // old way
        if (ack.getResult()) {
            uploadQueue.removeByMongoId(ack.getAction(), ack.get_id());
            rxBus.send(new EventNSClientNewLog("DBUPDATE/DBREMOVE", "Acked " + ack.get_id()));
        } else {
            rxBus.send(new EventNSClientNewLog("ERROR", "DBUPDATE/DBREMOVE Unknown response"));
        }
    }

    public void processAuthAck(NSAuthAck ack) {
        String connectionStatus = "Authenticated (";
        if (ack.read) connectionStatus += "R";
        if (ack.write) connectionStatus += "W";
        if (ack.write_treatment) connectionStatus += "T";
        connectionStatus += ')';
        isConnected = true;
        hasWriteAuth = ack.write && ack.write_treatment;
        rxBus.send(new EventNSClientStatus(connectionStatus));
        rxBus.send(new EventNSClientNewLog("AUTH", connectionStatus));
        if (!ack.write) {
            rxBus.send(new EventNSClientNewLog("ERROR", "Write permission not granted !!!!"));
        }
        if (!ack.write_treatment) {
            rxBus.send(new EventNSClientNewLog("ERROR", "Write treatment permission not granted !!!!"));
        }
        if (!hasWriteAuth) {
            Notification noperm = new Notification(Notification.NSCLIENT_NO_WRITE_PERMISSION, resourceHelper.gs(R.string.nowritepermission), Notification.URGENT);
            rxBus.send(new EventNewNotification(noperm));
        } else {
            rxBus.send(new EventDismissNotification(Notification.NSCLIENT_NO_WRITE_PERMISSION));
        }
    }

    public class LocalBinder extends Binder {
        public NSClientService getServiceInstance() {
            return NSClientService.this;
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    public void initialize() {
        dataCounter = 0;

        readPreferences();

        if (!nsAPISecret.equals(""))
            nsAPIhashCode = Hashing.sha1().hashString(nsAPISecret, Charsets.UTF_8).toString();

        rxBus.send(new EventNSClientStatus("Initializing"));
        if (!nsClientPlugin.isAllowed()) {
            rxBus.send(new EventNSClientNewLog("NSCLIENT", "not allowed"));
            rxBus.send(new EventNSClientStatus("Not allowed"));
        } else if (nsClientPlugin.paused) {
            rxBus.send(new EventNSClientNewLog("NSCLIENT", "paused"));
            rxBus.send(new EventNSClientStatus("Paused"));
        } else if (!nsEnabled) {
            rxBus.send(new EventNSClientNewLog("NSCLIENT", "disabled"));
            rxBus.send(new EventNSClientStatus("Disabled"));
        } else if (!nsURL.equals("") && (buildHelper.isEngineeringMode() || nsURL.toLowerCase().startsWith("https://"))) {
            try {
                rxBus.send(new EventNSClientStatus("Connecting ..."));
                IO.Options opt = new IO.Options();
                opt.forceNew = true;
                opt.reconnection = true;
                mSocket = IO.socket(nsURL, opt);
                mSocket.on(Socket.EVENT_CONNECT, onConnect);
                mSocket.on(Socket.EVENT_DISCONNECT, onDisconnect);
                mSocket.on(Socket.EVENT_ERROR, onError);
                mSocket.on(Socket.EVENT_CONNECT_ERROR, onError);
                mSocket.on(Socket.EVENT_CONNECT_TIMEOUT, onError);
                mSocket.on(Socket.EVENT_PING, onPing);
                rxBus.send(new EventNSClientNewLog("NSCLIENT", "do connect"));
                mSocket.connect();
                mSocket.on("dataUpdate", onDataUpdate);
                mSocket.on("announcement", onAnnouncement);
                mSocket.on("alarm", onAlarm);
                mSocket.on("urgent_alarm", onUrgentAlarm);
                mSocket.on("clear_alarm", onClearAlarm);
            } catch (URISyntaxException | RuntimeException e) {
                rxBus.send(new EventNSClientNewLog("NSCLIENT", "Wrong URL syntax"));
                rxBus.send(new EventNSClientStatus("Wrong URL syntax"));
            }
        } else if (nsURL.toLowerCase().startsWith("http://")) {
            rxBus.send(new EventNSClientNewLog("NSCLIENT", "NS URL not encrypted"));
            rxBus.send(new EventNSClientStatus("Not encrypted"));
        } else {
            rxBus.send(new EventNSClientNewLog("NSCLIENT", "No NS URL specified"));
            rxBus.send(new EventNSClientStatus("Not configured"));
        }
    }

    private final Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            connectCounter++;
            String socketId = mSocket != null ? mSocket.id() : "NULL";
            rxBus.send(new EventNSClientNewLog("NSCLIENT", "connect #" + connectCounter + " event. ID: " + socketId));
            if (mSocket != null)
                sendAuthMessage(new NSAuthAck(rxBus));
            watchdog();
        }
    };

    void watchdog() {
        synchronized (reconnections) {
            long now = DateUtil.now();
            reconnections.add(now);
            for (int i = 0; i < reconnections.size(); i++) {
                Long r = reconnections.get(i);
                if (r < now - T.mins(WATCHDOG_INTERVAL_MINUTES).msecs()) {
                    reconnections.remove(r);
                }
            }
            rxBus.send(new EventNSClientNewLog("WATCHDOG", "connections in last " + WATCHDOG_INTERVAL_MINUTES + " mins: " + reconnections.size() + "/" + WATCHDOG_MAX_CONNECTIONS));
            if (reconnections.size() >= WATCHDOG_MAX_CONNECTIONS) {
                Notification n = new Notification(Notification.NS_MALFUNCTION, resourceHelper.gs(R.string.nsmalfunction), Notification.URGENT);
                rxBus.send(new EventNewNotification(n));
                rxBus.send(new EventNSClientNewLog("WATCHDOG", "pausing for " + WATCHDOG_RECONNECT_IN + " mins"));
                nsClientPlugin.pause(true);
                rxBus.send(new EventNSClientUpdateGUI());
                new Thread(() -> {
                    SystemClock.sleep(T.mins(WATCHDOG_RECONNECT_IN).msecs());
                    rxBus.send(new EventNSClientNewLog("WATCHDOG", "reenabling NSClient"));
                    nsClientPlugin.pause(false);
                }).start();
            }
        }
    }

    private final Emitter.Listener onDisconnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            aapsLogger.debug(LTag.NSCLIENT, "disconnect reason: {}", args);
            rxBus.send(new EventNSClientNewLog("NSCLIENT", "disconnect event"));
        }
    };

    public synchronized void destroy() {
        if (mSocket != null) {
            mSocket.off(Socket.EVENT_CONNECT);
            mSocket.off(Socket.EVENT_DISCONNECT);
            mSocket.off(Socket.EVENT_PING);
            mSocket.off("dataUpdate");
            mSocket.off("announcement");
            mSocket.off("alarm");
            mSocket.off("urgent_alarm");
            mSocket.off("clear_alarm");

            rxBus.send(new EventNSClientNewLog("NSCLIENT", "destroy"));
            isConnected = false;
            hasWriteAuth = false;
            mSocket.disconnect();
            mSocket = null;
        }
    }


    public void sendAuthMessage(NSAuthAck ack) {
        JSONObject authMessage = new JSONObject();
        try {
            authMessage.put("client", "Android_" + nsDevice);
            authMessage.put("history", nsHours);
            authMessage.put("status", true); // receive status
            authMessage.put("from", latestDateInReceivedData); // send data newer than
            authMessage.put("secret", nsAPIhashCode);
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
            return;
        }
        rxBus.send(new EventNSClientNewLog("AUTH", "requesting auth"));
        if (mSocket != null)
            mSocket.emit("authorize", authMessage, ack);
    }

    public void readPreferences() {
        nsEnabled = nsClientPlugin.isEnabled(PluginType.GENERAL);
        nsURL = sp.getString(R.string.key_nsclientinternal_url, "");
        nsAPISecret = sp.getString(R.string.key_nsclientinternal_api_secret, "");
        nsDevice = sp.getString("careportal_enteredby", "");
    }

    private final Emitter.Listener onError = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            String msg = "Unknown Error";
            if (args.length > 0 && args[0] != null) {
                msg = args[0].toString();
            }
            rxBus.send(new EventNSClientNewLog("ERROR", msg));
        }
    };

    private final Emitter.Listener onPing = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            rxBus.send(new EventNSClientNewLog("PING", "received"));
            // send data if there is something waiting
            resend("Ping received");
        }
    };

    private final Emitter.Listener onAnnouncement = new Emitter.Listener() {
        /*
        {
        "level":0,
        "title":"Announcement",
        "message":"test",
        "plugin":{"name":"treatmentnotify","label":"Treatment Notifications","pluginType":"notification","enabled":true},
        "group":"Announcement",
        "isAnnouncement":true,
        "key":"9ac46ad9a1dcda79dd87dae418fce0e7955c68da"
        }
         */
        @Override
        public void call(final Object... args) {
            JSONObject data;
            try {
                data = (JSONObject) args[0];
                handleAnnouncement(data);
            } catch (Exception e) {
                aapsLogger.error("Unhandled exception", e);
            }
        }
    };

    private final Emitter.Listener onAlarm = new Emitter.Listener() {
        /*
        {
        "level":1,
        "title":"Warning HIGH",
        "message":"BG Now: 5 -0.2 → mmol\/L\nRaw BG: 4.8 mmol\/L Čistý\nBG 15m: 4.8 mmol\/L\nIOB: -0.02U\nCOB: 0g",
        "eventName":"high",
        "plugin":{"name":"simplealarms","label":"Simple Alarms","pluginType":"notification","enabled":true},
        "pushoverSound":"climb",
        "debug":{"lastSGV":5,"thresholds":{"bgHigh":180,"bgTargetTop":75,"bgTargetBottom":72,"bgLow":70}},
        "group":"default",
        "key":"simplealarms_1"
        }
         */
        @Override
        public void call(final Object... args) {
            JSONObject data;
            try {
                data = (JSONObject) args[0];
                handleAlarm(data);
            } catch (Exception e) {
                aapsLogger.error("Unhandled exception", e);
            }
        }
    };

    private final Emitter.Listener onUrgentAlarm = args -> {
        JSONObject data;
        try {
            data = (JSONObject) args[0];
            handleUrgentAlarm(data);
        } catch (Exception e) {
            aapsLogger.error("Unhandled exception", e);
        }
    };

    private final Emitter.Listener onClearAlarm = new Emitter.Listener() {
        /*
        {
        "clear":true,
        "title":"All Clear",
        "message":"default - Urgent was ack'd",
        "group":"default"
        }
         */
        @Override
        public void call(final Object... args) {
            JSONObject data;
            try {
                data = (JSONObject) args[0];
                rxBus.send(new EventNSClientNewLog("CLEARALARM", "received"));
                rxBus.send(new EventDismissNotification(Notification.NS_ALARM));
                rxBus.send(new EventDismissNotification(Notification.NS_URGENT_ALARM));
                aapsLogger.debug(LTag.NSCLIENT, data.toString());
            } catch (Exception e) {
                aapsLogger.error("Unhandled exception", e);
            }
        }
    };

    private final Emitter.Listener onDataUpdate = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            NSClientService.handler.post(() -> {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "AndroidAPS:NSClientService_onDataUpdate");
                wakeLock.acquire();
                try {

                    JSONObject data = (JSONObject) args[0];
                    boolean broadcastProfile = false;
                    try {
                        // delta means only increment/changes are comming
                        boolean isDelta = data.has("delta");
                        boolean isFull = !isDelta;
                        rxBus.send(new EventNSClientNewLog("DATA", "Data packet #" + dataCounter++ + (isDelta ? " delta" : " full")));

                        if (data.has("status")) {
                            JSONObject status = data.getJSONObject("status");
                            nsSettingsStatus.handleNewData(status);
                        } else if (!isDelta) {
                            rxBus.send(new EventNSClientNewLog("ERROR", "Unsupported Nightscout version !!!!"));
                        }

                        if (data.has("profiles")) {
                            JSONArray profiles = data.getJSONArray("profiles");
                            if (profiles.length() > 0) {
                                // take the newest
                                JSONObject profileStoreJson = (JSONObject) profiles.get(profiles.length() - 1);
                                rxBus.send(new EventNSClientNewLog("PROFILE", "profile received"));
                                dataWorker.enqueue(
                                        new OneTimeWorkRequest.Builder(NSProfilePlugin.NSProfileWorker.class)
                                                .setInputData(dataWorker.storeInputData(profileStoreJson, null))
                                                .build());

                                if (sp.getBoolean(R.string.key_nsclient_localbroadcasts, false)) {
                                    Bundle bundle = new Bundle();
                                    bundle.putString("profile", profileStoreJson.toString());
                                    bundle.putBoolean("delta", isDelta);
                                    Intent intent = new Intent(Intents.ACTION_NEW_PROFILE);
                                    intent.putExtras(bundle);
                                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                    sendBroadcast(intent);
                                }
                            }
                        }

                        if (data.has("treatments")) {
                            JSONArray treatments = data.getJSONArray("treatments");
                            JSONArray removedTreatments = new JSONArray();
                            JSONArray addedOrUpdatedTreatments = new JSONArray();
                            if (treatments.length() > 0)
                                rxBus.send(new EventNSClientNewLog("DATA", "received " + treatments.length() + " treatments"));
                            for (Integer index = 0; index < treatments.length(); index++) {
                                JSONObject jsonTreatment = treatments.getJSONObject(index);
                                String action = JsonHelper.safeGetStringAllowNull(jsonTreatment, "action", null);
                                long mills = JsonHelper.safeGetLong(jsonTreatment, "mills");

                                if (action == null) addedOrUpdatedTreatments.put(jsonTreatment);
                                else if (action.equals("update"))
                                    addedOrUpdatedTreatments.put(jsonTreatment);
                                else if (action.equals("remove") && mills > dateUtil._now() - T.days(1).msecs()) // handle 1 day old deletions only
                                    removedTreatments.put(jsonTreatment);
                            }
                            if (removedTreatments.length() > 0) {
                                dataWorker.enqueue(
                                        new OneTimeWorkRequest.Builder(NSClientRemoveWorker.class)
                                                .setInputData(dataWorker.storeInputData(removedTreatments, null))
                                                .build());

                                if (sp.getBoolean(R.string.key_nsclient_localbroadcasts, false)) {
                                    Bundle bundle = new Bundle();
                                    bundle.putString("treatments", removedTreatments.toString());
                                    bundle.putBoolean("delta", isDelta);
                                    Intent intent = new Intent(Intents.ACTION_REMOVED_TREATMENT);
                                    intent.putExtras(bundle);
                                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                    sendBroadcast(intent);
                                }
                            }
                            if (addedOrUpdatedTreatments.length() > 0) {
                                dataWorker.enqueue(
                                        new OneTimeWorkRequest.Builder(NSClientAddUpdateWorker.class)
                                                .setInputData(dataWorker.storeInputData(addedOrUpdatedTreatments, null))
                                                .build());

                                if (sp.getBoolean(R.string.key_nsclient_localbroadcasts, false)) {
                                    List<JSONArray> splitted = splitArray(addedOrUpdatedTreatments);
                                    for (JSONArray part : splitted) {
                                        Bundle bundle = new Bundle();
                                        bundle.putString("treatments", part.toString());
                                        bundle.putBoolean("delta", isDelta);
                                        Intent intent = new Intent(Intents.ACTION_CHANGED_TREATMENT);
                                        intent.putExtras(bundle);
                                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                        sendBroadcast(intent);
                                    }
                                }
                            }
                        }
                        if (data.has("devicestatus")) {
                            JSONArray devicestatuses = data.getJSONArray("devicestatus");
                            if (devicestatuses.length() > 0) {
                                rxBus.send(new EventNSClientNewLog("DATA", "received " + devicestatuses.length() + " device statuses"));
                                nsDeviceStatus.handleNewData(devicestatuses);
                            }
                        }
                        if (data.has("food")) {
                            JSONArray foods = data.getJSONArray("food");
                            if (foods.length() > 0)
                                rxBus.send(new EventNSClientNewLog("DATA", "received " + foods.length() + " foods"));
                            dataWorker.enqueue(
                                    new OneTimeWorkRequest.Builder(FoodPlugin.FoodWorker.class)
                                            .setInputData(dataWorker.storeInputData(foods, null))
                                            .build());
                        }
                        //noinspection SpellCheckingInspection
                        if (data.has("mbgs")) {
                            JSONArray mbgArray = data.getJSONArray("mbgs");
                            if (mbgArray.length() > 0)
                                rxBus.send(new EventNSClientNewLog("DATA", "received " + mbgArray.length() + " mbgs"));
                            dataWorker.enqueue(
                                    new OneTimeWorkRequest.Builder(NSClientMbgWorker.class)
                                            .setInputData(dataWorker.storeInputData(mbgArray, null))
                                            .build());
                        }
                        if (data.has("cals")) {
                            JSONArray cals = data.getJSONArray("cals");
                            if (cals.length() > 0)
                                rxBus.send(new EventNSClientNewLog("DATA", "received " + cals.length() + " cals"));
                            // Calibrations ignored
                        }
                        if (data.has("sgvs")) {
                            JSONArray sgvs = data.getJSONArray("sgvs");
                            if (sgvs.length() > 0)
                                rxBus.send(new EventNSClientNewLog("DATA", "received " + sgvs.length() + " sgvs"));

                            dataWorker.enqueue(new OneTimeWorkRequest.Builder(NSClientSourcePlugin.NSClientSourceWorker.class)
                                    .setInputData(dataWorker.storeInputData(sgvs, null))
                                    .build());

                            List<JSONArray> splitted = splitArray(sgvs);
                            if (sp.getBoolean(R.string.key_nsclient_localbroadcasts, false)) {
                                for (JSONArray part : splitted) {
                                    Bundle bundle = new Bundle();
                                    bundle.putString("sgvs", part.toString());
                                    bundle.putBoolean("delta", isDelta);
                                    Intent intent = new Intent(Intents.ACTION_NEW_SGV);
                                    intent.putExtras(bundle);
                                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                    sendBroadcast(intent);
                                }
                            }
                        }
                        rxBus.send(new EventNSClientNewLog("LAST", dateUtil.dateAndTimeString(latestDateInReceivedData)));
                    } catch (JSONException e) {
                        aapsLogger.error("Unhandled exception", e);
                    }
                    //rxBus.send(new EventNSClientNewLog("NSCLIENT", "onDataUpdate end");
                } finally {
                    if (wakeLock.isHeld()) wakeLock.release();
                }
            });
        }
    };

    public void dbUpdate(DbRequest dbr, NSUpdateAck ack) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", dbr.collection);
            message.put("_id", dbr._id);
            message.put("data", new JSONObject(dbr.data));
            mSocket.emit("dbUpdate", message, ack);
            rxBus.send(new EventNSClientNewLog("DBUPDATE " + dbr.collection, "Sent " + dbr._id));
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public void dbUpdate(String collection, String _id, JSONObject data, Object originalObject) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", collection);
            message.put("_id", _id);
            message.put("data", data);
            mSocket.emit("dbUpdate", message, new NSUpdateAck("dbUpdate", _id, aapsLogger, rxBus, originalObject));
            rxBus.send(new EventNSClientNewLog("DBUPDATE " + collection, "Sent " + originalObject.getClass().getSimpleName() + " " + _id));
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public void dbRemove(DbRequest dbr, NSUpdateAck ack) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", dbr.collection);
            message.put("_id", dbr._id);
            mSocket.emit("dbRemove", message, ack);
            rxBus.send(new EventNSClientNewLog("DBREMOVE " + dbr.collection, "Sent " + dbr._id));
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public void dbRemove(String collection, String _id, Object originalObject) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", collection);
            message.put("_id", _id);
            mSocket.emit("dbRemove", message, new NSUpdateAck("dbRemove", _id, aapsLogger, rxBus, originalObject));
            rxBus.send(new EventNSClientNewLog("DBREMOVE " + collection, "Sent " + originalObject.getClass().getSimpleName() + " " + _id));
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public void dbAdd(DbRequest dbr, NSAddAck ack) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", dbr.collection);
            message.put("data", new JSONObject(dbr.data));
            mSocket.emit("dbAdd", message, ack);
            rxBus.send(new EventNSClientNewLog("DBADD " + dbr.collection, "Sent " + dbr.nsClientID));
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public void dbAdd(String collection, JSONObject data, Object originalObject) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", collection);
            message.put("data", data);
            mSocket.emit("dbAdd", message, new NSAddAck(aapsLogger, rxBus, originalObject));
            rxBus.send(new EventNSClientNewLog("DBADD " + collection, "Sent " + originalObject.getClass().getSimpleName() + " " + data));
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public void sendAlarmAck(AlarmAck alarmAck) {
        if (!isConnected || !hasWriteAuth) return;
        mSocket.emit("ack", alarmAck.level, alarmAck.group, alarmAck.silenceTime);
        rxBus.send(new EventNSClientNewLog("ALARMACK ", alarmAck.level + " " + alarmAck.group + " " + alarmAck.silenceTime));
    }

    public void resend(final String reason) {
        if (!isConnected || !hasWriteAuth) return;

        handler.post(() -> {
            if (mSocket == null || !mSocket.connected()) return;

            rxBus.send(new EventNSClientNewLog("QUEUE", "Resend started: " + reason));

            if (lastAckTime > System.currentTimeMillis() - 10 * 1000L) {
                aapsLogger.debug(LTag.NSCLIENT, "Skipping resend by lastAckTime: " + ((System.currentTimeMillis() - lastAckTime) / 1000L) + " sec");
                return;
            }

            dataSyncSelector.processChangedBolusesCompat();
            dataSyncSelector.processChangedCarbsCompat();
            dataSyncSelector.processChangedBolusCalculatorResultsCompat();
            dataSyncSelector.processChangedTemporaryBasalsCompat();
            dataSyncSelector.processChangedExtendedBolusesCompat();
            dataSyncSelector.processChangedGlucoseValuesCompat();
            dataSyncSelector.processChangedTempTargetsCompat();
            dataSyncSelector.processChangedFoodsCompat();
            dataSyncSelector.processChangedTherapyEventsCompat();
            dataSyncSelector.processChangedDeviceStatusesCompat();

            if (uploadQueue.size() == 0)
                return;

            if (lastResendTime > System.currentTimeMillis() - 10 * 1000L) {
                aapsLogger.debug(LTag.NSCLIENT, "Skipping resend by lastResendTime: " + ((System.currentTimeMillis() - lastResendTime) / 1000L) + " sec");
                return;
            }
            lastResendTime = System.currentTimeMillis();

            CloseableIterator<DbRequest> iterator;
            int maxcount = 30;
            try {
                iterator = databaseHelper.getDbRequestIterator();
                try {
                    while (iterator.hasNext() && maxcount > 0) {
                        DbRequest dbr = iterator.next();
                        if (dbr.action.equals("dbAdd")) {
                            NSAddAck addAck = new NSAddAck(aapsLogger, rxBus, null);
                            dbAdd(dbr, addAck);
                        } else if (dbr.action.equals("dbRemove")) {
                            NSUpdateAck removeAck = new NSUpdateAck("dbRemove", dbr._id, aapsLogger, rxBus, null);
                            dbRemove(dbr, removeAck);
                        } else if (dbr.action.equals("dbUpdate")) {
                            NSUpdateAck updateAck = new NSUpdateAck("dbUpdate", dbr._id, aapsLogger, rxBus, null);
                            dbUpdate(dbr, updateAck);
                        }
                        maxcount--;
                    }
                } finally {
                    iterator.close();
                }
            } catch (SQLException e) {
                aapsLogger.error("Unhandled exception", e);
            }

            rxBus.send(new EventNSClientNewLog("QUEUE", "Resend ended: " + reason));
        });
    }

    public void restart() {
        destroy();
        initialize();
    }

    private void handleAnnouncement(JSONObject announcement) {
        boolean defaultVal = config.getNSCLIENT();
        if (sp.getBoolean(R.string.key_ns_announcements, defaultVal)) {
            NSAlarm nsAlarm = new NSAlarm(announcement);
            Notification notification = new NotificationWithAction(injector, nsAlarm);
            rxBus.send(new EventNewNotification(notification));
            rxBus.send(new EventNSClientNewLog("ANNOUNCEMENT", JsonHelper.safeGetString(announcement, "message", "received")));
            aapsLogger.debug(LTag.NSCLIENT, announcement.toString());
        }
    }

    private void handleAlarm(JSONObject alarm) {
        boolean defaultVal = config.getNSCLIENT();
        if (sp.getBoolean(R.string.key_ns_alarms, defaultVal)) {
            long snoozedTo = sp.getLong(R.string.key_snoozedTo, 0L);
            if (snoozedTo == 0L || System.currentTimeMillis() > snoozedTo) {
                NSAlarm nsAlarm = new NSAlarm(alarm);
                Notification notification = new NotificationWithAction(injector, nsAlarm);
                rxBus.send(new EventNewNotification(notification));
            }
            rxBus.send(new EventNSClientNewLog("ALARM", JsonHelper.safeGetString(alarm, "message", "received")));
            aapsLogger.debug(LTag.NSCLIENT, alarm.toString());
        }
    }

    private void handleUrgentAlarm(JSONObject alarm) {
        boolean defaultVal = config.getNSCLIENT();
        if (sp.getBoolean(R.string.key_ns_alarms, defaultVal)) {
            long snoozedTo = sp.getLong(R.string.key_snoozedTo, 0L);
            if (snoozedTo == 0L || System.currentTimeMillis() > snoozedTo) {
                NSAlarm nsAlarm = new NSAlarm(alarm);
                Notification notification = new NotificationWithAction(injector, nsAlarm);
                rxBus.send(new EventNewNotification(notification));
            }
            rxBus.send(new EventNSClientNewLog("URGENTALARM", JsonHelper.safeGetString(alarm, "message", "received")));
            aapsLogger.debug(LTag.NSCLIENT, alarm.toString());
        }
    }

    public List<JSONArray> splitArray(JSONArray array) {
        List<JSONArray> ret = new ArrayList<>();
        try {
            int size = array.length();
            int count = 0;
            JSONArray newarr = null;
            for (int i = 0; i < size; i++) {
                if (count == 0) {
                    if (newarr != null) {
                        ret.add(newarr);
                    }
                    newarr = new JSONArray();
                    count = 20;
                }
                newarr.put(array.get(i));
                --count;
            }
            if (newarr != null && newarr.length() > 0) {
                ret.add(newarr);
            }
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
            ret = new ArrayList<>();
            ret.add(array);
        }
        return ret;
    }
}