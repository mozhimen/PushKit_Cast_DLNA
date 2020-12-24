package com.android.cast.dlna.controller;

import com.android.cast.dlna.OnDeviceRegistryListener;
import com.android.cast.dlna.controller.ConnectSession.ConnectSessionCallback;
import com.android.cast.dlna.controller.action.ActionCallbackListener;
import com.android.cast.dlna.controller.action.ICastActionFactory;
import com.android.cast.dlna.controller.action.ICastActionFactory.CastActionFactory;
import com.android.cast.dlna.device.CastDevice;
import com.android.cast.dlna.util.CastUtils;
import com.android.cast.dlna.util.ILogger;
import com.android.cast.dlna.util.ILogger.DefaultLoggerImpl;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportState;

/**
 *
 */
public class CastControlImp implements ICastControl, OnDeviceRegistryListener {

    private final ILogger mLogger = new DefaultLoggerImpl(this);
    private ControlPoint mControlPoint;
    private ICastActionFactory mCastActionFactory;
    private final ICastEventListener mCastEventListener;
    private ICastSession mMediaSession;
    private ICastSession mConnectSession;

    private CastDevice mCastDevice;
    private MediaInfo mMediaInfo;
    private CastDevice mSessionTimeoutDevice;
    private PositionInfo mPositionInfo;
    @CastStatus
    private int mCastStatus = CastControlImp.IDLE;
    private boolean mConnected = false;

    public CastControlImp(AndroidUpnpService service, ICastEventListener listener) {

        mControlPoint = service.getControlPoint();
        mCastEventListener = new CastEventListener(listener);
    }

    public void bindNLUpnpCastService(AndroidUpnpService service) {
        mControlPoint = service.getControlPoint();

        if (isConnected()) {
            // if (mConnectSession == null) {
            //     mConnectSession = new ConnectSession(mControlPoint, mCastActionFactory, mConnectSessionCallback);
            // }
            //
            // mConnectSession.start();
        }
    }

    public void unbindNLUpnpCastService() {
        endMediaSession();

        if (mConnectSession != null) {
            mConnectSession.stop();
        }

        mConnectSession = null;

        mControlPoint = null;
    }

    private final ConnectSessionCallback mConnectSessionCallback = new ConnectSessionCallback() {
        @Override
        public void onCastSession(TransportInfo transportInfo, MediaInfo mediaInfo, int volume) {
            mMediaInfo = mediaInfo;

            if (!mConnected) {
                mCastEventListener.onConnected(mCastDevice, transportInfo, mediaInfo, volume);
            }

            mConnected = true;

            if (TransportState.PLAYING.getValue().equals(transportInfo.getCurrentTransportState().getValue()) ||
                    TransportState.PAUSED_PLAYBACK.getValue().equals(transportInfo.getCurrentTransportState().getValue())) {
                if (mMediaSession == null || !mMediaSession.isRunning()) {
                    startMediaSession();
                }
            } else {
                endMediaSession();
            }
        }

        @Override
        public void onCastSessionTimeout() {
            mSessionTimeoutDevice = mCastDevice;

            disconnect();
        }
    };

    // ------------------------------------------------------------------------------------------------
    // control
    // ------------------------------------------------------------------------------------------------
    @Override
    public void connect(CastDevice castDevice) {
        if (mCastDevice != null) disconnect();
        mLogger.i(String.format("connecting ==> [%s@%s]", castDevice.getName(), castDevice.getId()));

        mCastDevice = castDevice;
        mCastEventListener.onConnecting(mCastDevice);
        mCastActionFactory = new CastActionFactory(castDevice);
        mConnectSession = new ConnectSession(mControlPoint, mCastActionFactory, mConnectSessionCallback);
        mConnectSession.start();
        mSessionTimeoutDevice = null;
    }

    @Override
    public void disconnect() {
        final CastDevice device = mCastDevice;
        mCastDevice = null;
        mConnected = false;
        if (device == null) return;

        mLogger.w(String.format("disconnect ==> [%s@%s]", device.getName(), device.getId()));
        endMediaSession();
        if (mConnectSession != null) {
            mConnectSession.stop();
        }
        mCastEventListener.onDisconnect();
    }

    @Override
    public void onDeviceAdded(CastDevice device) {
        if (mSessionTimeoutDevice != null && mSessionTimeoutDevice.equals(device)) {
            connect(device);
        }
    }

    @Override
    public void onDeviceUpdated(CastDevice device) {
    }

    @Override
    public void onDeviceRemoved(CastDevice device) {
        if (isConnected() && mCastDevice.equals(device)) {
            mSessionTimeoutDevice = device;

            disconnect();
        }
    }

    @Override
    public boolean isConnected() {
        return checkConnection();
    }

    public CastDevice getCastDevice() {
        return mCastDevice;
    }

    // ------------------------------------------------------------------------------------------------
    // control
    // ------------------------------------------------------------------------------------------------
    @Override
    public void cast(final CastObject castObject) {
        if (checkConnection()) {
            ActionCallback action = mCastActionFactory.getAvService().setCastAction(new ActionCallbackListener() {
                @Override
                public void success(ActionInvocation<?> invocation, Object... received) {
                    mCastEventListener.onCast(castObject);

                    startMediaSession();
                }

                @Override
                public void failure(ActionInvocation<?> invocation, UpnpResponse operation, String defaultMsg) {
                    endMediaSession();

                    mCastEventListener.onError(defaultMsg);
                }

            }, castObject.url, CastUtils.getMetadata(castObject));

            mControlPoint.execute(action);
        }
    }

    @Override
    public void start() {
        if (checkConnection()) {
            ActionCallback action = mCastActionFactory.getAvService().playAction(new ActionCallbackListener() {
                @Override
                public void success(ActionInvocation<?> invocation, Object... received) {
                    mCastEventListener.onStart();
                }
            });

            mControlPoint.execute(action);
        }
    }

    @Override
    public void pause() {
        if (checkConnection()) {
            ActionCallback action = mCastActionFactory.getAvService().pauseAction(new ActionCallbackListener() {
                @Override
                public void success(ActionInvocation<?> invocation, Object... received) {
                    mCastEventListener.onPause();
                }
            });

            mControlPoint.execute(action);
        }
    }

    @Override
    public void stop() {
        if (checkConnection()) {
            ActionCallback action = mCastActionFactory.getAvService().stopAction(new ActionCallbackListener() {
                @Override
                public void success(ActionInvocation<?> invocation, Object... received) {
                    mCastEventListener.onStop();
                }
            });

            mControlPoint.execute(action);
        }
    }

    @Override
    public void seekTo(long position) {
        if (checkConnection()) {
            ActionCallback action = mCastActionFactory.getAvService().seekAction(new ActionCallbackListener() {
                @Override
                public void success(ActionInvocation<?> invocation, Object... received) {
                    mCastEventListener.onSeekTo((long) (received[0]));
                }
            }, position);

            mControlPoint.execute(action);
        }
    }

    @Override
    public void setVolume(int percent) {
        if (checkConnection()) {
            ActionCallback action = mCastActionFactory.getRenderService().setVolumeAction(new ActionCallbackListener() {
                @Override
                public void success(ActionInvocation<?> invocation, Object... received) {
                    mCastEventListener.onVolume((int) (received[0]));
                }
            }, percent);

            mControlPoint.execute(action);
        }
    }

    @Override
    public void setBrightness(int percent) {
        if (checkConnection()) {
            ActionCallback action = mCastActionFactory.getRenderService().setBrightnessAction(new ActionCallbackListener() {
                @Override
                public void success(ActionInvocation<?> invocation, Object... received) {
                    mCastEventListener.onBrightness((int) (received[0]));
                }
            }, percent);

            mControlPoint.execute(action);
        }
    }

    @Override
    public int getCastStatus() {
        return mCastStatus;
    }

    @Override
    public PositionInfo getPosition() {
        return mPositionInfo;
    }

    @Override
    public MediaInfo getMedia() {
        return mMediaInfo;
    }

    private boolean checkConnection() {
        return mCastDevice != null && mControlPoint != null;
    }

    private void startMediaSession() {
        if (mMediaSession != null) {
            mMediaSession.stop();
        }

        // mMediaSession = new MediaSession(mControlPoint, mCastActionFactory, mCastEventListener);
        //
        // mMediaSession.start();
    }

    private void endMediaSession() {
        if (mMediaSession != null) {
            mMediaSession.stop();
        }

        mMediaSession = null;

        mPositionInfo = null;
    }

    // --------------------------------------------------------------------------------------------------------
    // Event listener wrapper
    // --------------------------------------------------------------------------------------------------------
    private class CastEventListener extends CastEventListenerWrapper {
        CastEventListener(ICastEventListener listener) {
            super(listener);
        }

        @Override
        public void onCast(CastObject castObject) {
            mCastStatus = CastControlImp.CASTING;

            super.onCast(castObject);
        }

        @Override
        public void onStart() {
            mCastStatus = CastControlImp.PLAY;

            super.onStart();
        }

        @Override
        public void onPause() {
            mCastStatus = CastControlImp.PAUSE;

            super.onPause();
        }

        @Override
        public void onStop() {
            mCastStatus = CastControlImp.STOP;

            super.onStop();

            endMediaSession();
        }

        @Override
        public void onError(String errorMsg) {
            mCastStatus = CastControlImp.ERROR;

            super.onError(errorMsg);

            endMediaSession();
        }

        @Override
        public void onUpdatePositionInfo(PositionInfo positionInfo) {
            mPositionInfo = positionInfo;

            super.onUpdatePositionInfo(positionInfo);
        }
    }
}
