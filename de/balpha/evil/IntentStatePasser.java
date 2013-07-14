package de.balpha.evil;

/*
Copyright (c) 2013, Benjamin Dumke-von der Ehe

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
*/

import android.content.Context;
import android.content.Intent;
import android.os.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Allows you to launch a new activity via an intent, and also pass in a savedInstanceState bundle
 * to be passed in to the relevant methods at the same time.
 *
 * DISCLAIMER: THIS IS BASICALLY MONKEY-PATCHING ANDROID. THERE PROBABLY BE DRAGONS.
 *
 * It uses reflection to modify an event in the message queue, which for obvious reasons is
 * not a supported way of interacting with Android.
 *
 * Also note the following:
 *
 * Fail gracefully if it doesn't work (i.e. you get a null savedInstanceState in your onCreate() etc.).
 * Even though I tested it successfully, the code cannot guarantee that it always works. It will only wait
 * for one second for the LAUNCH_ACTIVITY message to appear in the queue. If for whatever reason it takes
 * longer, the code will assume that it missed the message. In Jelly Bean this is actually unlikely, since we
 * use a synchronization mechnism introduced in that version. In earlier SDKs, this isn't available. I
 * haven't dug deeply enough into the guts of Android to check whether we're guaranteed to find the message
 * in the queue, but I think that we're not if the timing is unfortunate.
 *
 * Also note that everything needs to happen in the same UI thread, i.e. you can't use this for e.g. launching
 * from inside one app an activity inside a different app.
 *
 * Tested on an HTC One X running Jelly Bean (4.1.1) and on an HTC Desire runing Gingerbread.
 *
 * Usage:
 *
 *     Intent intent = new Intent(oldActivity, NewActivity.class);
 *     Bundle savedInstanceState = getTheSavedStateFromWherever();
 *     IntentStatePasser.startActivityWithState(oldActivity, intent, savedInstanceState);
 *
 */
public class IntentStatePasser {

    public static void startActivityWithState(Context context, Intent intent, Bundle state) {
        if (Looper.getMainLooper() != Looper.myLooper())
            throw new RuntimeException("startActivityWithState must be called from the UI thread");
        new IntentStatePasser(context, intent, state).run();
    }

    private long intentId = getNextIntentId();
    private long runUntil;
    private Intent intent;
    private Bundle state;
    private Context context;

    private Integer syncBarrier = null;

    private IntentStatePasser(Context c, Intent i, Bundle s) {
        context = c;
        intent = i;
        state = s;
    }

    private void run() {
        if (intent.hasExtra(INTENT_IDENTIFIER))
            throw new RuntimeException("same intent passed to IntentStatePasser multiple times");

        try {
            reflect();
            if (state == null)
                return;
            if (!goodToRun)
                return;

            intent.putExtra(INTENT_IDENTIFIER, intentId);
            runUntil = SystemClock.uptimeMillis() + 1000;
            postSyncBarrier();
            if (!goodToRun)
                return;
            CheckQueueHandler handler = new CheckQueueHandler();
            handler.sendMessage(getAsyncMessage());
        } finally {
            context.startActivity(intent);
        }
    }

    private Message getAsyncMessage() {
        Message msg = Message.obtain();
        if (syncBarrierAvailable) {
            try {
                Message_setAsynchronous.invoke(msg, true);
            } catch (IllegalAccessException e) {
                goodToRun = false;
            } catch (InvocationTargetException e) {
                goodToRun = false;
            }
        }
        return msg;
    }

    private void postSyncBarrier() {
        if (!syncBarrierAvailable)
            return;
        if (syncBarrier != null)
            throw new RuntimeException("postSyncBarrier called multiple times");

        try {
            syncBarrier = (Integer)Looper_postSyncBarrier.invoke(Looper.myLooper());
        } catch (IllegalAccessException e) {
            syncBarrierAvailable = false;
        } catch (InvocationTargetException e) {
            syncBarrierAvailable = false;
        } catch (ClassCastException e) {
            syncBarrierAvailable = false;
        }
    }

    private void removeSyncBarrier() {
        if (!syncBarrierAvailable)
            return;
        if (syncBarrier == null)
            throw new RuntimeException("removeSyncBarrier called without a posted barrier");

        try {
            Looper_removeSyncBarrier.invoke(Looper.myLooper(), syncBarrier);
        } catch (IllegalAccessException e) {
            // If -- for whatever reason -- we were able to add the barrier, but not remove it,
            // that means we're screwed and the app will freeze.
            goodToRun = false;
            throw new RuntimeException("Unable to remove posted sync barrier", e);
        } catch (InvocationTargetException e) {
            goodToRun = false;
            throw new RuntimeException("Unable to remove posted sync barrier", e);
        }
        syncBarrier = null;
    }

    private class CheckQueueHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            if (!goodToRun || SystemClock.uptimeMillis() > runUntil) {
                removeSyncBarrier();
                return;
            }

            boolean found = false;
            try {
                found = processMessageQueue();
            } catch (IllegalAccessException e) {
                goodToRun = false;
            }
            if (!goodToRun) {
                removeSyncBarrier();
                return;
            }

            if (found) {
                removeSyncBarrier();
            } else {
                Message nextMessage = getAsyncMessage();
                if (syncBarrierAvailable)
                    this.sendMessageDelayed(nextMessage, 50);
                else
                    this.sendMessage(nextMessage);
            }
        }

        private boolean processMessageQueue() throws IllegalAccessException {
            Message msg = (Message)MessageQueue_mMessages.get(Looper.myQueue());
            while (msg != null) {
                if (msg.what == LAUNCH_ACTIVITY && msg.obj != null && isActivityClientRecord(msg.obj)) {
                    if (!goodToRun)
                        return false;

                    Object msgIntent = ActivityClientRecord_intent.get(msg.obj);
                    if (msgIntent instanceof Intent && ((Intent)msgIntent).getLongExtra(INTENT_IDENTIFIER, -1) == intentId) {
                        if (ActivityClientRecord_state.get(msg.obj) == null) {
                            ActivityClientRecord_state.set(msg.obj, state);
                            return true;
                        }
                    }
                }
                msg = (Message)Message_next.get(msg);
            }
            return false;
        }
    }



    private static final String INTENT_IDENTIFIER = "de.balpha.evil:intent_identifier";
    private static final int LAUNCH_ACTIVITY = 100; // defined in android.app.ActivityThread$H

    private static long lastIntentId = SystemClock.uptimeMillis();

    private static synchronized long getNextIntentId() {
        lastIntentId++;
        return lastIntentId;
    }

    private static boolean goodToRun = true;
    private static boolean syncBarrierAvailable = false;
    private static boolean haveReflected = false;

    private static Field MessageQueue_mMessages;
    private static Method Message_setAsynchronous;
    private static Field Message_next;
    private static Method Looper_postSyncBarrier;
    private static Method Looper_removeSyncBarrier;

    private static final String ActivityClientRecord_ClassName = "android.app.ActivityThread$ActivityClientRecord";
    private static Class ActivityClientRecord_Class;
    private static Field ActivityClientRecord_intent;
    private static Field ActivityClientRecord_state;

    private static void reflectOptional() throws NoSuchMethodException {
        // These are actually public; just hidden. Still can't hurt to be future proof
        // for the case they're made private.
        Message_setAsynchronous = Message.class.getDeclaredMethod("setAsynchronous", Boolean.TYPE);
        Message_setAsynchronous.setAccessible(true);
        Looper_postSyncBarrier = Looper.class.getDeclaredMethod("postSyncBarrier");
        Looper_postSyncBarrier.setAccessible(true);
        Looper_removeSyncBarrier = Looper.class.getDeclaredMethod("removeSyncBarrier", Integer.TYPE);
        Looper_removeSyncBarrier.setAccessible(true);
    }

    private static void reflectMandatory() throws NoSuchFieldException {
        MessageQueue_mMessages = MessageQueue.class.getDeclaredField("mMessages");
        MessageQueue_mMessages.setAccessible(true);
        Message_next = Message.class.getDeclaredField("next");
        Message_next.setAccessible(true);
    }

    private static void reflectActivityClientRecord(Object o) throws NoSuchFieldException {
        if (!o.getClass().getName().equals(ActivityClientRecord_ClassName))
            return;

        ActivityClientRecord_Class = o.getClass();
        ActivityClientRecord_intent = ActivityClientRecord_Class.getDeclaredField("intent");
        ActivityClientRecord_intent.setAccessible(true);
        ActivityClientRecord_state = ActivityClientRecord_Class.getDeclaredField("state");
        ActivityClientRecord_state.setAccessible(true);
    }

    private static boolean isActivityClientRecord(Object o) {
        if (ActivityClientRecord_Class == null) {
            try {
                reflectActivityClientRecord(o);
            } catch (NoSuchFieldException e) {
                // If we're here that means the class name matched, but the fields didn't.
                // Uh-oh. We're outa here.
                goodToRun = false;
                return false;
            }
        }
        return o.getClass() == ActivityClientRecord_Class;
    }

    private static void reflect() {
        if (haveReflected)
            return;
        haveReflected = true;

        try {
            reflectMandatory();
            goodToRun = true;
        } catch (NoSuchFieldException e) {
            return;
        }

        try {
            reflectOptional();
            syncBarrierAvailable = true;
        } catch (NoSuchMethodException e) {
            // this page intentionally left blank
        }


    }
}
