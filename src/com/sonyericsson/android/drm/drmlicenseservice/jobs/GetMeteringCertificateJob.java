/* ***** BEGIN LICENSE BLOCK *****
* Version: MPL 1.1
*
* The contents of this file are subject to the Mozilla Public License Version
* 1.1 (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
* http://www.mozilla.org/MPL/
*
* Software distributed under the License is distributed on an "AS IS" basis,
* WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
* for the specific language governing rights and limitations under the
* License.
*
* The Original Code is DRM License Service.
*
* The Initial Developer of the Original Code is
* Sony Ericsson Mobile Communications AB.
* Portions created by Sony Ericsson Mobile Communications AB are Copyright (C) 2011
* Sony Ericsson Mobile Communications AB.
* Portions created by Sony Mobile Communications AB are Copyright (C) 2012
* Sony Mobile Communications AB. All Rights Reserved.
*
* Contributor(s):
*
* ***** END LICENSE BLOCK ***** */

package com.sonyericsson.android.drm.drmlicenseservice.jobs;

import com.sonyericsson.android.drm.drmlicenseservice.Constants;
import com.sonyericsson.android.drm.drmlicenseservice.DatabaseConstants;
import com.sonyericsson.android.drm.drmlicenseservice.DrmJobDatabase;

import android.content.ContentValues;
import android.database.Cursor;
import android.drm.DrmInfo;
import android.drm.DrmInfoRequest;

public class GetMeteringCertificateJob extends StackableJob {
    private String mCertificateServer = null;
    private String mMeteringId = null;
    private String mCustomData = "";

    private DrmInfoRequest createRequestToGenerateMeterCertChallenge(String mimeType) {
        DrmInfoRequest request = new DrmInfoRequest(DrmInfoRequest.TYPE_RIGHTS_ACQUISITION_INFO,
                mimeType);
        request.put(Constants.DRM_ACTION, Constants.DRM_ACTION_GENERATE_METER_CERT_CHALLENGE);
        request.put(Constants.DRM_METERING_METERING_ID, mMeteringId);
        addCustomData(request, mCustomData);
        return request;
    }

    private DrmInfoRequest createRequestToProcessMeterCertResponse(String mimeType, String data) {
        DrmInfoRequest request = new DrmInfoRequest(DrmInfoRequest.TYPE_RIGHTS_ACQUISITION_INFO,
                mimeType);
        request.put(Constants.DRM_ACTION, Constants.DRM_ACTION_PROCESS_METER_CERT_RESPONSE);
        request.put(Constants.DRM_DATA, data);
        return request;
    }

    public GetMeteringCertificateJob(String certificateServer, String meteringId,
            String customData) {
        mCertificateServer = certificateServer;
        mMeteringId = meteringId;
        if (customData != null) {
            mCustomData = customData;
        }
    }

    // Overall logic
    // 1 generate Metering Certificate Challenge
    // 2 send Metering Certificate request to server
    // 3 process Metering Certificate response
    // if 2 fails then handle error and return

    @Override
    public boolean executeNormal() {
        boolean status = false;
        if (mCertificateServer != null && mCertificateServer.length() > 0 &&
                mMeteringId != null && mMeteringId.length() > 0) {
            // Send message to engine to get the Metering Certificate challenge
            DrmInfo reply = sendInfoRequest(createRequestToGenerateMeterCertChallenge(
                    Constants.DRM_DLS_PIFF_MIME));
            if (reply == null) {
                reply = sendInfoRequest(createRequestToGenerateMeterCertChallenge(
                        Constants.DRM_DLS_MIME));
            }
            if (reply != null) {
                String replyStatus = (String)reply.get(Constants.DRM_STATUS);
                if (replyStatus != null && replyStatus.length() > 0 && replyStatus.equals("ok")) {
                    String data = (String)reply.get(Constants.DRM_DATA);
                    // Post license challenge to server
                    status = postMessage(mCertificateServer, data);
                } else {
                    mJobManager.addParameter(Constants.DRM_KEYPARAM_HTTP_ERROR, -6);
                }
            } else {
                mJobManager.addParameter(Constants.DRM_KEYPARAM_HTTP_ERROR, -6);
            }
        }
        return status;
    }

    @Override
    protected boolean handleResponse200(String data) {
        boolean isOk = false;
        // Send message to engine to process metering certificate
        DrmInfo reply = sendInfoRequest(createRequestToProcessMeterCertResponse(
                Constants.DRM_DLS_PIFF_MIME, data));
        if (reply == null) {
            reply = sendInfoRequest(createRequestToProcessMeterCertResponse(
                    Constants.DRM_DLS_MIME, data));
        }
        if (reply != null) {
            String replyStatus = (String)reply.get(Constants.DRM_STATUS);
            if (replyStatus != null && replyStatus.length() > 0 && replyStatus.equals("ok")) {
                // Certificate is stored
                isOk = true;
            } else {
                mJobManager.addParameter(Constants.DRM_KEYPARAM_HTTP_ERROR, -6);
            }
        } else {
            mJobManager.addParameter(Constants.DRM_KEYPARAM_HTTP_ERROR, -6);
        }
        return isOk;
    }

    @Override
    public boolean writeToDB(DrmJobDatabase jobDb) {
        boolean status = true;
        ContentValues values = new ContentValues();
        values.put(DatabaseConstants.COLUMN_TASKS_NAME_TYPE,
                DatabaseConstants.JOBTYPE_GET_METERING_CERTIFICATE);
        values.put(DatabaseConstants.COLUMN_TASKS_NAME_GRP_ID, this.getGroupId());
        if (mJobManager != null) {
            values.put(DatabaseConstants.COLUMN_TASKS_NAME_SESSION_ID, mJobManager.getSessionId());
        }
        values.put(DatabaseConstants.COLUMN_TASKS_NAME_GENERAL1, this.mCertificateServer);
        values.put(DatabaseConstants.COLUMN_TASKS_NAME_GENERAL2, this.mMeteringId);
        values.put(DatabaseConstants.COLUMN_TASKS_NAME_GENERAL3, this.mCustomData);
        values.put(DatabaseConstants.COLUMN_TASKS_NAME_GRP_ID, this.getGroupId());
        long result = jobDb.insert(values);
        if (result  != -1) {
            super.setDatabaseId(result);
        } else {
            status = false;
        }
        return status;
    }

    @Override
    public boolean readFromDB(Cursor c) {
        this.mCertificateServer = c.getString(DatabaseConstants.COLUMN_GETMETERING_CERT_SERVER);
        this.mMeteringId = c.getString(DatabaseConstants.COLUMN_GETMETERING_METERING_ID);
        this.mCustomData = c.getString(DatabaseConstants.COLUMN_GETMETERING_CUSTOM_DATA);
        this.setGroupId(c.getInt(DatabaseConstants.COLUMN_TASKS_POS_GRP_ID));
        return true;
    }
}
