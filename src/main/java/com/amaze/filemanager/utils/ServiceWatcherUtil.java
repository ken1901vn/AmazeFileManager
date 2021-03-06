package com.amaze.filemanager.utils;

/**
 * Created by vishal on 4/1/17.
 *
 * Helper class providing helper methods to manage Service startup and it's progress
 * Be advised - this class can only handle progress with one object at a time. Hence, class also provides
 * convenience methods to serialize the service startup.
 */

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.NotificationCompat;

import com.amaze.filemanager.R;
import com.amaze.filemanager.activities.BaseActivity;
import com.amaze.filemanager.services.ProgressHandler;

import java.util.ArrayList;

public class ServiceWatcherUtil {

    private Handler handler;
    private static HandlerThread handlerThread;
    private ProgressHandler progressHandler;
    long totalSize;

    private static ArrayList<Intent> pendingIntents = new ArrayList<>();

    // position of byte in total byte size to be copied
    public static long POSITION = 0L;

    /**
     *
     * @param progressHandler to publish progress after certain delay
     * @param totalSize total size of copy operation on files, so we know when to halt the watcher
     */
    public ServiceWatcherUtil(ProgressHandler progressHandler, long totalSize) {
        this.progressHandler = progressHandler;
        this.totalSize = totalSize;
        POSITION = 0l;

        handlerThread = new HandlerThread("service_progress_watcher");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    /**
     * Watches over the copy progress without interrupting the worker
     * {@link GenericCopyThread#thread} thread.
     * Method frees up all the resources and worker threads after copy operation completes.
     */
    public void watch() {
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {

                // we don't have a file name yet, wait for copy to start
                if (progressHandler.getFileName()==null) handler.postDelayed(this, 1000);

                progressHandler.addWrittenLength(POSITION);
                if (POSITION == totalSize || progressHandler.getCancelled()) {
                    // copy complete, free up resources
                    // we've finished the work or copy cancelled
                    handler.removeCallbacks(this);
                    handlerThread.quit();
                    return;
                }
                handler.postDelayed(this, 1000);
            }
        };

        handler.postDelayed(runnable, 1000);
    }

    /**
     * Convenience method to check whether another service is working in background
     * If a service is found working (by checking and maintaining state of {@link BaseActivity#IS_BOUND}
     * which is further bound to service using {@link android.content.ServiceConnection} for it's state)
     * then we wait for an interval of 5 secs, before checking on it again.
     * We're not depending on {@link BaseActivity#IS_BOUND} much as it becomes inconsistent after we
     * close the app, as it is bound to lifecycle of {@link android.app.Activity}.
     * {@see ServiceWatcherUtil#init()}
     *
     * Be advised - this method is not sure to start a new service, especially when app has been closed
     * as there are higher chances for android system to GC the thread when it is running low on memory
     *
     * @param context
     * @param intent
     */
    public static void runService(final Context context, final Intent intent) {

        if (!BaseActivity.IS_BOUND || handlerThread==null || !handlerThread.isAlive()) {
            // we're not bound, no need to proceed further and waste up resources
            // start the service directly
            context.startService(intent);
            return;
        }

        if (pendingIntents.size()==0) {
            init(context);
        }
        pendingIntents.add(intent);

    }

    /**
     * Helper method to {@link #runService(Context, Intent)}
     * Starts the wait watcher thread if not already started.
     * Halting condition depends on various things - a boolean {@link BaseActivity#IS_BOUND} and
     * {@link #handlerThread}, in case the boolean becomes inconsistent when Activity is closed (since
     * Service is bound to the activity).
     * @param context
     */
    private static void init(final Context context) {

        final HandlerThread waitingThread = new HandlerThread("service_startup_watcher");
        waitingThread.start();
        final Handler handler = new Handler(waitingThread.getLooper());
        final NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        final NotificationCompat.Builder mBuilder=new NotificationCompat.Builder(context);
        mBuilder.setContentTitle(context.getString(R.string.waiting_title));
        mBuilder.setContentText(context.getString(R.string.waiting_content));
        mBuilder.setAutoCancel(false);
        mBuilder.setSmallIcon(R.drawable.ic_content_copy_white_36dp);
        mBuilder.setProgress(0, 0, true);
        notificationManager.notify(9248, mBuilder.build());

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (!BaseActivity.IS_BOUND || handlerThread==null || !handlerThread.isAlive()) {

                    // service is been finished, let's start this one

                    // pop recent intent from pendingIntents
                    context.startService(pendingIntents.remove(pendingIntents.size()-1));

                    if (pendingIntents.size()==0) {
                        // we've done all the work, free up resources (if not already killed by system)
                        notificationManager.cancel(9248);
                        handler.removeCallbacks(this);
                        waitingThread.quit();
                        return;
                    }
                }
                handler.postDelayed(this, 5000);
            }
        };

        handler.postDelayed(runnable, 5000);
    }
}
