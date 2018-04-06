package com.twiliorn.library;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.TextureView;

import com.twilio.video.I420Frame;
import com.twilio.video.VideoRenderer;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.ThreadUtils;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;

public class VideoTextureView extends TextureView implements VideoRenderer, TextureView.SurfaceTextureListener {

            private static final String TAG = "VideoTextureView";

            // Cached resource name.
            private final String resourceName;
            private final RendererCommon.VideoLayoutMeasure videoLayoutMeasure =
                    new RendererCommon.VideoLayoutMeasure();
            private final EglRenderer eglRenderer;

            // Callback for reporting renderer events. Read-only after initilization so no lock required.
            private RendererCommon.RendererEvents rendererEvents = new RendererCommon.RendererEvents() {
                @Override
                public void onFirstFrameRendered() {
                    if (listener != null) {
                        listener.onFirstFrame();
                    }
                }

                @Override
                public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
                    if (listener != null) {
                        listener.onFrameDimensionsChanged(videoWidth, videoHeight, rotation);
                    }
                }
            };
            private VideoRenderer.Listener listener;

            private final Object layoutLock = new Object();
            private Handler uiThreadHandler = new Handler(Looper.getMainLooper());
            private boolean isFirstFrameRendered;
            private int rotatedFrameWidth;
            private int rotatedFrameHeight;
            private int frameRotation;

            // Accessed only on the main thread.
            private int surfaceWidth;
            private int surfaceHeight;

            private Object eglBaseProvider;
            private Field webRtcI420FrameField;

    public VideoTextureView(Context context) throws NoSuchFieldException {
                this(context, null);
            }

    public VideoTextureView(Context context, AttributeSet attrs) throws NoSuchFieldException {
                super(context, attrs);
                this.resourceName = getResourceName();
                eglRenderer = new EglRenderer(resourceName);
                setSurfaceTextureListener(this);
                webRtcI420FrameField = I420Frame.class.getDeclaredField("webRtcI420Frame");
                webRtcI420FrameField.setAccessible(true);
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                // Do not setup the renderer when using developer tools to avoid EGL14 runtime exceptions
                if(!isInEditMode()) {
                    eglBaseProvider = EglBaseProviderReflectionUtils.getEglBaseProvider(this);
                    init(EglBaseProviderReflectionUtils.getRootEglBaseContext(eglBaseProvider), rendererEvents);
                }
            }

            @Override
            protected void onDetachedFromWindow() {
                EglBaseProviderReflectionUtils.relaseEglBaseProvider(eglBaseProvider, this);
                super.onDetachedFromWindow();
            }

            /**
             * Set if the video stream should be mirrored or not.
             */
            public void setMirror(final boolean mirror) {
                eglRenderer.setMirror(mirror);
            }

            /**
             * Set how the video will fill the allowed layout area.
             */
            public void setScalingType(RendererCommon.ScalingType scalingType) {
                ThreadUtils.checkIsOnMainThread();
                videoLayoutMeasure.setScalingType(scalingType);
            }

            public void setScalingType(RendererCommon.ScalingType scalingTypeMatchOrientation,
                    RendererCommon.ScalingType scalingTypeMismatchOrientation) {
                ThreadUtils.checkIsOnMainThread();
                videoLayoutMeasure.setScalingType(scalingTypeMatchOrientation,
                        scalingTypeMismatchOrientation);
            }

            /**
             * Sets listener of rendering events.
             */
            public void setListener(VideoRenderer.Listener listener) {
                this.listener = listener;
            }

            @Override
            public void renderFrame(I420Frame frame) {
                updateFrameDimensionsAndReportEvents(frame);
                eglRenderer.renderFrame(getWebRtcI420Frame(frame));
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                ThreadUtils.checkIsOnMainThread();
                final Point size;
                synchronized (layoutLock) {
                    size = videoLayoutMeasure.measure(widthSpec,
                            heightSpec,
                            rotatedFrameWidth,
                            rotatedFrameHeight);
                }
                setMeasuredDimension(size.x, size.y);
                logV("onMeasure(). New size: " + size.x + "x" + size.y);
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                ThreadUtils.checkIsOnMainThread();
                eglRenderer.setLayoutAspectRatio((right - left) / (float) (bottom - top));
                updateSurfaceSize();
            }

            private void init(EglBase.Context sharedContext,
                    RendererCommon.RendererEvents rendererEvents) {
                init(sharedContext, rendererEvents, EglBase.CONFIG_PLAIN, new GlRectDrawer());
            }

            private void init(final EglBase.Context sharedContext,
            RendererCommon.RendererEvents rendererEvents,
            final int[] configAttributes,
            RendererCommon.GlDrawer drawer) {
                ThreadUtils.checkIsOnMainThread();
                this.rendererEvents = rendererEvents;
                synchronized (layoutLock) {
                    rotatedFrameWidth = 0;
                    rotatedFrameHeight = 0;
                    frameRotation = 0;
                }
                eglRenderer.init(sharedContext, configAttributes, drawer);
            }

            /*
             * Use reflection on I420 frame to get access to WebRTC frame since EglRenderer only renders
             * WebRTC frames.
             */
            private org.webrtc.VideoRenderer.I420Frame getWebRtcI420Frame(I420Frame i420Frame) {
                org.webrtc.VideoRenderer.I420Frame webRtcI420Frame = null;

                try {
                    webRtcI420Frame = (org.webrtc.VideoRenderer.I420Frame)
                            webRtcI420FrameField.get(i420Frame);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }

                return webRtcI420Frame;
            }

            private void updateSurfaceSize() {
                ThreadUtils.checkIsOnMainThread();
                synchronized (layoutLock) {
                    if (rotatedFrameWidth != 0 &&
                            rotatedFrameHeight != 0 && getWidth() != 0
                            && getHeight() != 0) {
                        final float layoutAspectRatio = getWidth() / (float) getHeight();
                        final float frameAspectRatio = rotatedFrameWidth / (float) rotatedFrameHeight;
                        final int drawnFrameWidth;
                        final int drawnFrameHeight;
                        if (frameAspectRatio > layoutAspectRatio) {
                            drawnFrameWidth = (int) (rotatedFrameHeight * layoutAspectRatio);
                            drawnFrameHeight = rotatedFrameHeight;
                        } else {
                            drawnFrameWidth = rotatedFrameWidth;
                            drawnFrameHeight = (int) (rotatedFrameWidth / layoutAspectRatio);
                        }
                        // Aspect ratio of the drawn frame and the view is the same.
                        final int width = Math.min(getWidth(), drawnFrameWidth);
                        final int height = Math.min(getHeight(), drawnFrameHeight);
                        logV("updateSurfaceSize. Layout size: " + getWidth() + "x" + getHeight() +
                                ", frame size: " + rotatedFrameWidth + "x" + rotatedFrameHeight +
                                ", requested surface size: " + width + "x" + height +
                                ", old surface size: " + surfaceWidth + "x" + surfaceHeight);
                        if (width != surfaceWidth || height != surfaceHeight) {
                            surfaceWidth = width;
                            surfaceHeight = height;
                        }
                    } else {
                        surfaceWidth = surfaceHeight = 0;
                    }
                }
            }

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                ThreadUtils.checkIsOnMainThread();
                eglRenderer.createEglSurface(surfaceTexture);
                surfaceWidth = width;
                surfaceHeight = height;
                updateSurfaceSize();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                ThreadUtils.checkIsOnMainThread();
                final CountDownLatch completionLatch = new CountDownLatch(1);
                eglRenderer.releaseEglSurface(new Runnable() {
                    @Override
                    public void run() {
                        completionLatch.countDown();
                    }
                });
                ThreadUtils.awaitUninterruptibly(completionLatch);
                return true;
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                ThreadUtils.checkIsOnMainThread();
                logV("surfaceChanged: size: " + width + "x" + height);
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                ThreadUtils.checkIsOnMainThread();
                logV("onSurfaceTextureUpdated");
            }

            private String getResourceName() {
                try {
                    return getResources().getResourceEntryName(getId()) + ": ";
                } catch (Resources.NotFoundException e) {
                    return "";
                }
            }

            // Update frame dimensions and report any changes to |rendererEvents|.
            private void updateFrameDimensionsAndReportEvents(I420Frame frame) {
                synchronized (layoutLock) {
                    if (!isFirstFrameRendered) {
                        isFirstFrameRendered = true;
                        logV("Reporting first rendered frame.");
                        if (rendererEvents != null) {
                            rendererEvents.onFirstFrameRendered();
                        }
                    }
                    if (rotatedFrameWidth != frame.rotatedWidth() ||
                            rotatedFrameHeight != frame.rotatedHeight() ||
                            frameRotation != frame.rotationDegree) {
                        logV("Reporting frame resolution changed to " + frame.width + "x" + frame.height
                                + " with rotation " + frame.rotationDegree);
                        if (rendererEvents != null) {
                            rendererEvents.onFrameResolutionChanged(frame.width,
                                    frame.height,
                                    frame.rotationDegree);
                        }
                        rotatedFrameWidth = frame.rotatedWidth();
                        rotatedFrameHeight = frame.rotatedHeight();
                        frameRotation = frame.rotationDegree;
                        uiThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                updateSurfaceSize();
                                requestLayout();
                            }
                        });
                    }
                }
            }

            private void logV(String string) {
                Logging.v(TAG, resourceName + string);
            }

            private void logD(String string) {
                Logging.d(TAG, resourceName + string);
            }
        }