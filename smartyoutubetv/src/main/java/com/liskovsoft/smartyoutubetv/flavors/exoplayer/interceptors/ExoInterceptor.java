package com.liskovsoft.smartyoutubetv.flavors.exoplayer.interceptors;

import android.content.Context;
import android.content.Intent;
import android.text.Html;
import android.text.InputType;
import android.webkit.WebResourceResponse;
import com.liskovsoft.m3uparser.core.utils.IOUtils;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.commands.GenericCommand;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.player.ExoPlayerFragment;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.wrappers.exoplayer.ExoPlayerWrapper;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.wrappers.externalplayer.ExternalPlayerWrapper;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.youtubeinfoparser.parsers.OnMediaFoundCallback;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.youtubeinfoparser.parsers.SimpleYouTubeInfoParser;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.youtubeinfoparser.parsers.YouTubeInfoParser;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.youtubeinfoparser.parsers.YouTubeMediaParser;
import com.liskovsoft.smartyoutubetv.fragments.TwoFragmentManager;
import com.liskovsoft.smartyoutubetv.interceptors.RequestInterceptor;
import com.liskovsoft.smartyoutubetv.misc.myquerystring.MyUrlEncodedQueryString;
import com.liskovsoft.smartyoutubetv.prefs.SmartPreferences;
import okhttp3.MediaType;

import java.io.InputStream;

public class ExoInterceptor extends RequestInterceptor {
    private final Context mContext;
    private static final String TAG = ExoInterceptor.class.getSimpleName();
    private final DelayedCommandCallInterceptor mDelayedInterceptor;
    private final BackgroundActionManager mManager;
    private final TwoFragmentManager mFragmentsManager;
    private OnMediaFoundCallback mExoCallback;
    private OnMediaFoundCallback mRealExoCallback;
    private final ExoNextInterceptor mNextInterceptor;
    private final HistoryInterceptor mHistoryInterceptor;
    private final SmartPreferences mPrefs;
    private final ActionsSender mSender;
    private String mCurrentUrl;
    public static final String URL_VIDEO_DATA = "get_video_info";
    public static final String URL_TV_TRANSPORT = "gen_204";

    public ExoInterceptor(Context context,
                          DelayedCommandCallInterceptor delayedInterceptor,
                          ExoNextInterceptor nextInterceptor,
                          HistoryInterceptor historyInterceptor) {
        super(context);
        
        mContext = context;
        mFragmentsManager = (TwoFragmentManager) context;
        mDelayedInterceptor = delayedInterceptor;
        mNextInterceptor = nextInterceptor;
        mHistoryInterceptor = historyInterceptor;
        mManager = new BackgroundActionManager(mFragmentsManager.getKeyHandler());
        mPrefs = SmartPreferences.instance(mContext);
        mSender = new ActionsSender(mContext, this);
        
        boolean useExternalPlayer = !SmartPreferences.USE_EXTERNAL_PLAYER_NONE.equals(mPrefs.getUseExternalPlayer());

        if (useExternalPlayer) {
            mExoCallback = ExternalPlayerWrapper.create(mContext, this);
        } else {
            mRealExoCallback = mExoCallback = new ExoPlayerWrapper(mContext, this);
        }
    }

    @Override
    public boolean test(String url) {
        return true;
    }

    @Override
    public WebResourceResponse intercept(String url) {
        Log.d(TAG, "Video intercepted: " + url);

        if (mRealExoCallback != null) { // video may be processed externally, so we need to restore
            mExoCallback = mRealExoCallback;
        }

        mCurrentUrl = unlockStreams(url);

        mManager.init(mCurrentUrl);

        // 'next' should not be fired at this point
        if (mManager.cancelPlayback()) {
            Log.d(TAG, "Video canceled: " + mCurrentUrl);

            if (mManager.isOpened()) { // return to player when suggestions doesn't work
                mExoCallback.onFalseCall();
            }

            return null;
        }

        return processCurrentUrl();
    }

    private WebResourceResponse processCurrentUrl() {
        mExoCallback.onStart();

        // Video title and other infos
        // long running code
        new Thread(() -> {
            mExoCallback.onMetadata(
                    mNextInterceptor.getMetadata(
                            mManager.getVideoId(mCurrentUrl),
                            mManager.getPlaylistId(mCurrentUrl)));
        }).start();

        // Clip content
        // long running code
        new Thread(() -> {
            try {
                parseAndOpenExoPlayer(getUrlData(mCurrentUrl));
            } catch (IllegalStateException e) {
                e.printStackTrace();
                MessageHelpers.showLongMessage(mContext, "Url doesn't exist or broken: " + mCurrentUrl);
            }
        }).start();

        return null;
    }

    /**
     * For parsing details see {@link YouTubeMediaParser}
     */
    private void parseAndOpenExoPlayer(InputStream inputStream) {
        final YouTubeInfoParser dataParser = new SimpleYouTubeInfoParser(mContext, inputStream);
        Log.d(TAG, "Video manifest received");
        dataParser.parse(mExoCallback);
    }

    public void updateLastCommand(GenericCommand command) {
        mDelayedInterceptor.setCommand(command);
        // force call command without adding to the history (in case WebView)
        mDelayedInterceptor.forceRun(true);
    }

    public TwoFragmentManager getFragmentsManager() {
        return mFragmentsManager;
    }

    public BackgroundActionManager getBackgroundActionManager() {
        return mManager;
    }

    public String getCurrentUrl() {
        return mCurrentUrl;
    }

    public void closeVideo() {
        if (mRealExoCallback != null) { // don't response in exo mode
            return;
        }

        Intent intent = new Intent();
        intent.putExtra(ExoPlayerFragment.BUTTON_BACK, true);
        mSender.bindActions(intent);
        mManager.onCancel();
    }

    public void jumpToNextVideo() {
        if (mRealExoCallback != null) { // don't response in exo mode
            return;
        }

        Intent intent = new Intent();
        intent.putExtra(ExoPlayerFragment.BUTTON_NEXT, true);
        mSender.bindActions(intent);
        mManager.onContinue();
    }

    public HistoryInterceptor getHistoryInterceptor() {
        return mHistoryInterceptor;
    }
    
    private String unlockStreams(String url) {
        //MyUrlEncodedQueryString query = MyUrlEncodedQueryString.parse(url);

        switch(mPrefs.getCurrentVideoType()) {
            case SmartPreferences.VIDEO_TYPE_DEFAULT:
                //query.remove("el"); // unlock age restricted videos but locks some streams (use carefully)
                //url = url.replace("&el=leanback", "");
                break;
            case SmartPreferences.VIDEO_TYPE_LIVE:
            case SmartPreferences.VIDEO_TYPE_UPCOMING:
            case SmartPreferences.VIDEO_TYPE_UNDEFINED:
                //query.remove("access_token"); // needed to unlock some personal uploaded videos
                //query.set("el", "leanback");
                //query.set("ps", "leanback");
                //query.set("c", "HTML5"); // needed to unlock streams
                //query.remove("c"); // needed to unlock streams

                // NOTE: don't unlock streams in video_info_interceptor.js
                // otherwise you'll get errors in youtube client
                url = url.replace("&c=TVHTML5", "&c=HTML5");

                break;
        }

        return url;
    }

    /**
     * Unlocking most of 4K mp4 formats.
     * It is done by removing c=TVHTML5 query param.
     * @param url
     * @return
     */
    private String unlockHlsStreamsOld(String url) {
        MyUrlEncodedQueryString query = MyUrlEncodedQueryString.parse(url);

        query.set("c", "HTML5");

        return query.toString();
    }

    /**
     * Unlocking most of 4K mp4 formats.
     * It is done by removing c=TVHTML5 query param.
     * @param url
     * @return
     */
    private String unlock60FpsFormats(String url) {
        MyUrlEncodedQueryString query = MyUrlEncodedQueryString.parse(url);

        query.set("el", "info"); // unlock dashmpd url
        query.set("ps", "default"); // unlock 60fps formats

        return query.toString();
    }

    private void removeUnusedParams(MyUrlEncodedQueryString query) {
        query.remove("cpn");
        query.remove("itct");
        query.remove("ei");
        query.remove("hl");
        query.remove("lact");
        query.remove("cos");
        query.remove("cosver");
        query.remove("cplatform");
        query.remove("width");
        query.remove("height");
        query.remove("cbrver");
        query.remove("ctheme");
        query.remove("cmodel");
        query.remove("cnetwork");
        query.remove("c");
        query.remove("cver");
        query.remove("cplayer");
        query.remove("cbrand");
        query.remove("cbr");
        query.remove("el");
        query.remove("ps");
    }

    public void openExternally(OnMediaFoundCallback playerWrapper) {
        mExoCallback = playerWrapper;
        processCurrentUrl();
    }
}
