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

package com.sonyericsson.android.drm.drmlicenseservice;

import com.sonyericsson.android.drm.drmlicenseservice.jobs.DrmFeedbackJob;
import com.sonyericsson.android.drm.drmlicenseservice.jobs.RenewRightsJob;
import com.sonyericsson.android.drm.drmlicenseservice.jobs.WebInitiatorJob;
import com.sonyericsson.android.drm.drmlicenseservice.IDrmLicenseService;
import com.sonyericsson.android.drm.drmlicenseservice.IDrmLicenseServiceCallback;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.drm.DrmErrorEvent;
import android.drm.DrmInfoEvent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;

/**
 * Service for handling license renewal. Handles two intent actions - renew and
 * save license.
 */
public class DrmLicenseService extends Service {

    private Context mContext = null;

    private HashMap<Long, JobManager> mJobs = new HashMap<Long, JobManager>();

    private DrmJobDatabase jobDb = null;

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = getBaseContext();
        synchronized (this) {
            if (jobDb == null) {
                jobDb = DrmJobDatabase.getInstance(this);
            }

            if (jobDb == null) {
                stopSelf();
            }
        }
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        synchronized (DrmLicenseService.this) {
            String intentAction = null;
            if (intent != null) {
                intentAction = intent.getAction();
                if (Constants.DEBUG) {
                    Log.d(Constants.LOGTAG, "Starting command " + intent);
                }

                if (intentAction == null) {
                    Log.e(Constants.LOGTAG, "intentAction is null");
                    ServiceUtility.sendOnInfoResult(this,
                            DrmErrorEvent.TYPE_RIGHTS_NOT_INSTALLED, null);
                } else {
                    if (intentAction.equals(Constants.INTENT_ACTION_DRM_SERVICE_RENEW)) {
                        renewRights(intent);
                    } else if (intentAction
                            .equals(Constants.INTENT_ACTION_DRM_SERVICE_HANDLE_WEB_INITIATOR)) {
                        handleWebInitiator(intent);
                    } else if (intentAction.equals(DrmLicenseActivity.class.getName())) {
                        // DrmLicenseActivity is calling for renewal of rights
                        String type = intent.getType();
                        if (type != null
                                && type.equalsIgnoreCase(Constants.DRM_DLS_INITIATOR_MIME)) {
                            handleWebInitiator(intent);
                        }
                    } else if (intentAction.equals(Intent.ACTION_BOOT_COMPLETED)) {
                        jobDb.purgeDatabase();
                        if (Constants.DEBUG) {
                            Log.d(Constants.LOGTAG, "Job database is purged");
                        }
                        stopSelf();
                    }
                }
            } else {
                if (Constants.DEBUG) {
                    Log.e(Constants.LOGTAG, "Action is not supported");
                }
                ServiceUtility.sendOnInfoResult(this,
                        DrmErrorEvent.TYPE_NOT_SUPPORTED, null);
            }
        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (Constants.DEBUG) {
            Log.d(Constants.LOGTAG, "Service stopped and destroyed");
        }
    }

    private void renewRights(final Intent intent) {
        final Uri fileUri = intent.getData();
        if (fileUri != null && !fileUri.equals(Uri.EMPTY)) {
            final String filePath = fileUri.getEncodedPath();

            final Handler jobManagerDoneHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (Constants.DEBUG) {
                        if (msg != null && msg.obj != null) {
                            Log.d(Constants.LOGTAG,
                                    "JobManager is done!  Result was: " + msg.obj.toString());
                        } else {
                            Log.d(Constants.LOGTAG, "JobManager is done!  Result was null");

                        }
                    }
                }
            };

            ServiceUtility.sendOnInfoResult(
                    this, DrmInfoEvent.TYPE_WAIT_FOR_RIGHTS, filePath);

            final Context fContext = this;
            new Thread() {
                public void run() {
                    super.run();
                    JobManager jm = new JobManager(fContext, jobManagerDoneHandler, 0, jobDb);
                    jm.pushJob(new RenewRightsJob(fileUri));
                    jm.start();
                }
            }.start();
        } else {
            ServiceUtility.sendOnInfoResult(this, DrmErrorEvent.TYPE_RIGHTS_NOT_INSTALLED, null);
        }
    }

    private void handleWebInitiator(final Intent intent) {
        final Uri uri = intent.getData();
        String mime = intent.getType();
        final Handler jobManagerDoneHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (Constants.DEBUG) {
                    if (msg != null && msg.obj != null) {
                        Log.d(Constants.LOGTAG,
                                "JobManager is done!  Result was: " + msg.obj.toString());
                    } else {
                        Log.d(Constants.LOGTAG, "JobManager is done!  Result was null");
                    }
                }
            }
        };

        if (mime != null && mime.equals("application/vnd.ms-playready.initiator+xml")) {
            if (uri != null && uri.getScheme() != null) {
                final Context fContext = this;
                new Thread() {
                    public void run() {
                        super.run();
                        JobManager jm = new JobManager(fContext, jobManagerDoneHandler, 0, jobDb);
                        jm.pushJob(new WebInitiatorJob(uri));
                        jm.start();
                    }
                }.start();

                // Create a toast that is shown when WebInitiator link was tapped by user
                // in Browser, or when a downloaded WI was tapped in notification bar.
                int resId = R.string.status_start_download;
                Toast.makeText(fContext, resId, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Return the interface
        return mBinder;
    }

    private final IDrmLicenseService.Stub mBinder = new IDrmLicenseService.Stub() {
        public long handleWebInitiator(Uri uri, Bundle parameters,
                IDrmLicenseServiceCallback callbackHandler) throws RemoteException {
            if (uri == null) {
                return 0;
            }
            JobManager jm = new JobManager(
                    mContext, null, parameters, callbackHandler, 0, jobDb);
            jm.pushJob(new DrmFeedbackJob(Constants.PROGRESS_TYPE_FINISHED_WEBINI));
            jm.pushJob(new WebInitiatorJob(uri));
            synchronized (mJobs) {
                mJobs.put(Long.valueOf(jm.getSessionId()), jm);
            }
            jm.registerOnFinishCallback(jm.new JobManagerFinishCallback() {
                @Override
                void isDone(long sessionId) {
                    synchronized (mJobs) {
                        mJobs.remove(sessionId);
                    }
                }
            });
            jm.start();
            return jm.getSessionId();
        }

        public long renewRights(Uri fileUri, Bundle parameters,
                IDrmLicenseServiceCallback callbackHandler) throws RemoteException {
            if (fileUri == null) {
                return 0;
            }
            JobManager jm = new JobManager(
                    mContext, null, parameters, callbackHandler, 0, jobDb);
            jm.pushJob(new RenewRightsJob(fileUri));
            synchronized (mJobs) {
                mJobs.put(Long.valueOf(jm.getSessionId()), jm);
            }
            jm.registerOnFinishCallback(jm.new JobManagerFinishCallback() {
                @Override
                void isDone(long sessionId) {
                    synchronized (mJobs) {
                        mJobs.remove(sessionId);
                    }
                }
            });
            jm.start();
            return jm.getSessionId();
        }

        public boolean setCallbackListener(
                IDrmLicenseServiceCallback callbackHandler, final long sessionId, Bundle parameters)
                throws RemoteException {
            boolean isOk = false;
            if (callbackHandler != null && sessionId != 0) {
                final JobManager jm = new JobManager(mContext, null, parameters, callbackHandler, 0,
                        jobDb);
                jm.mSessionId = sessionId;
                jm.restoreState();
                jm.registerOnFinishCallback(jm.new JobManagerFinishCallback() {
                    @Override
                    void isDone(long sessionId) {
                        synchronized (mJobs) {
                            mJobs.remove(sessionId);
                        }
                    }
                });

                Looper.prepare();

                class StatusHandler extends Handler {
                    public boolean jobAreBeingAdded = false;
                }

                final StatusHandler statusHandler = new StatusHandler() {
                    @Override
                    public void handleMessage(Message msg) {
                        jobAreBeingAdded = Boolean.parseBoolean(msg.obj.toString());
                        getLooper().quit();
                    }
                };
                new Thread () {
                    public void run() {
                        if (readInStoredJobs(jm, sessionId, statusHandler)) {
                            synchronized (mJobs) {
                                mJobs.put(Long.valueOf(jm.getSessionId()), jm);
                            }
                            jm.start();
                        }
                    }
                }.start();
                Looper.loop();
                isOk = statusHandler.jobAreBeingAdded;
            }
            return isOk;
        }

        public boolean cancelSession(long sessionId) throws RemoteException {
            JobManager jm;
            synchronized (mJobs) {
                jm = mJobs.get(Long.valueOf(sessionId));
            }
            if (jm != null) {
                jm.prepareCancel();
                return true;
            }
            return false;
        }

    };

    private boolean readInStoredJobs(final JobManager jm, final long sessionId,
            Handler statusHandler) {
        boolean jobsAdded = false;
        if (jm != null) {
            if (jobDb.addDatabaseJobsToStack(jm, sessionId, statusHandler) > 0 ) {
                Log.d(Constants.LOGTAG,
                "Starting to work on the non-finished tasks found in database");
                jobsAdded = true;
            }
        }
        return jobsAdded;
    }
}
