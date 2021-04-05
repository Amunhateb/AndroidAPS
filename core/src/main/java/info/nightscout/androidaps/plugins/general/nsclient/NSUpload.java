package info.nightscout.androidaps.plugins.general.nsclient;

import org.json.JSONException;
import org.json.JSONObject;

import javax.inject.Inject;
import javax.inject.Singleton;

import info.nightscout.androidaps.core.R;
import info.nightscout.androidaps.database.entities.TherapyEvent;
import info.nightscout.androidaps.db.DbRequest;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.interfaces.UploadQueueInterface;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.sharedPreferences.SP;

/**
 * Created by mike on 26.05.2017.
 */
@Singleton
public class NSUpload {

    private final AAPSLogger aapsLogger;
    private final SP sp;
    private final UploadQueueInterface uploadQueue;

    @Inject
    public NSUpload(
            AAPSLogger aapsLogger,
            SP sp,
            UploadQueueInterface uploadQueue
    ) {
        this.aapsLogger = aapsLogger;
        this.sp = sp;
        this.uploadQueue = uploadQueue;
    }

    public void uploadProfileSwitch(ProfileSwitch profileSwitch, long nsClientId) {
        try {
            JSONObject data = getJson(profileSwitch);
            DbRequest dbr = new DbRequest("dbAdd", "treatments", data, nsClientId);
            aapsLogger.debug("Prepared: " + dbr.log());
            uploadQueue.add(dbr);
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public void updateProfileSwitch(ProfileSwitch profileSwitch) {
        try {
            JSONObject data = getJson(profileSwitch);
            if (profileSwitch._id != null) {
                uploadQueue.add(new DbRequest("dbUpdate", "treatments", profileSwitch._id, data, profileSwitch.date));
            }
        } catch (JSONException e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    private static JSONObject getJson(ProfileSwitch profileSwitch) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("eventType", TherapyEvent.Type.PROFILE_SWITCH.getText());
        data.put("duration", profileSwitch.durationInMinutes);
        data.put("profile", profileSwitch.getCustomizedName());
        data.put("profileJson", profileSwitch.profileJson);
        data.put("profilePlugin", profileSwitch.profilePlugin);
        if (profileSwitch.isCPP) {
            data.put("CircadianPercentageProfile", true);
            data.put("timeshift", profileSwitch.timeshift);
            data.put("percentage", profileSwitch.percentage);
        }
        data.put("created_at", DateUtil.toISOString(profileSwitch.date));
        data.put("enteredBy", "AndroidAPS");

        return data;
    }

    // TODO replace with setting isValid = false
    public void removeCareportalEntryFromNS(String _id) {
        uploadQueue.add(new DbRequest("dbRemove", "treatments", _id, System.currentTimeMillis()));
    }

    public void uploadProfileStore(JSONObject profileStore) {
        if (sp.getBoolean(R.string.key_ns_uploadlocalprofile, false)) {
            uploadQueue.add(new DbRequest("dbAdd", "profile", profileStore, System.currentTimeMillis()));
        }
    }

    public static boolean isIdValid(String _id) {
        if (_id == null)
            return false;
        return _id.length() == 24;
    }

}
