package de.balpha.evil;

import android.os.AsyncTask;
import android.os.Looper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public final class EvilHacks {

    /**
     * Calls .get() on the given future and returns the result without blocking the UI thread.
     *
     * I STRONGLY ADVISE AGAINS ACTUALLY USING THIS. UNTIL PROVEN OTHERWISE, ASSUME IT KILLS KITTENS.
     *
     * It is a horrible hack that is pretty much guaranteed to cause unexpected effects, far into undocumented
     * behavior territory. Consider it a proof of concept.
     *
     * Example use (using awaitNoThrow, which is like await but returns null instead of throwing .get()'s checked exceptions):
     *
     *     public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
     *         var view = inflater.inflate(R.layout.my_layout, container, false);
     *         Future&lt;String&gt; titleFuture = myTitleService.retrieveTitleFromSomeWebApi(); // calling .get() on this would freeze the UI
     *         String title = EvilHacks.awaitNoThrow(titleFuture); // UI stays responsive while getting the result
     *         if (title == null)
     *             title = "(no title available)"
     *         ((TextView)view.findViewById(R.id.my_text_view)).setText(title);
     *         return view;
     *     }
     */
    public static <T> T await(final Future<T> future) throws InterruptedException, ExecutionException {

        if (Looper.getMainLooper() != Looper.myLooper())
            throw new RuntimeException("await is meant to be called from the UI thread");

        final IAmDone done = new IAmDone();

        new AsyncTask<Void, Void, T>() {
            private Throwable mException;

            @Override
            protected T doInBackground(Void... voids) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    mException = e;
                } catch (ExecutionException e) {
                    mException = e;
                }
                return null;
            }

            @Override
            protected void onPostExecute(T result) {
                if (mException != null)
                    done.setException(mException);
                else
                    done.setResult(result);

                throw done;
            }
        }.execute();

        try {
            Looper.loop();
        } catch (IAmDone d) {
            if (d != done)
                throw d;

            Throwable inner = d.getException();
            if (inner != null) {
                if (inner instanceof InterruptedException)
                    throw (InterruptedException)inner;
                else if (inner instanceof ExecutionException)
                    throw (ExecutionException)inner;
            }
            return (T)d.getResult();
        }
        return null; // unless the looper quits, we won't get here.
    }

    public static <T> T awaitNoThrow(final Future<T> future) {
        try {
            return await(future);
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    private static class IAmDone extends RuntimeException {
        private Object mResult; // have to use Object instead of making the class generic because the latter isn't allowed when extending Throwable
        private Throwable mException;

        private Throwable getException() {
            return mException;
        }

        private void setException(Throwable e) {
            mException = e;
        }

        void setResult(Object r) {
            mResult = r;
        }
        Object getResult() {
            return mResult;
        }
    }
}
