package com.flyzebra.screenrecord.module;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.flyzebra.screenrecord.utils.ByteUtil;
import com.flyzebra.screenrecord.utils.FlyLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Author FlyZebra
 * 2019/6/18 16:12
 * Describ:
 **/
public class ScreenRecorder {
    private MediaProjection mMediaProjection;
    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;

    // parameters for the encoder
    private int mWidth = 1024;
    private int mHeight = 600;
    private static final String MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 25; // 30 fps
    private static final int IFRAME_INTERVAL = 5; // 2 seconds between I-frames
    private static final int TIMEOUT_US = 10000;
    private final int BIT_RATE = (int) (mWidth * mHeight * 3.6);

    private Surface mSurface;
    private VirtualDisplay mVirtualDisplay;
    private AtomicBoolean isStop = new AtomicBoolean(true);
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private long startTime = 0;
    private int videoTrackIndex;

    private static final HandlerThread sWorkerThread = new HandlerThread("screen-recorder");

    static {
        sWorkerThread.start();
    }

    private static final Handler tHandler = new Handler(sWorkerThread.getLooper());
    private Runnable handleTask = new Runnable() {
        @Override
        public void run() {
            while (isRunning.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            isRunning.set(true);
            while (!isStop.get()) {
                int eobIndex = mediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_US);
                switch (eobIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        FlyLog.v("VideoSenderThread,MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED");
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        FlyLog.v("VideoSenderThread,MediaCodec.INFO_TRY_AGAIN_LATER");
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        FlyLog.v("VideoSenderThread,MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:" + mediaCodec.getOutputFormat().toString());
//                    sendAVCDecoderConfigurationRecord(0, mediaCodec.getOutputFormat());
                        if (!isStop.get()) {
                            initMediaMuxer();
                            videoTrackIndex = mediaMuxer.addTrack(mediaCodec.getOutputFormat());
                            mediaMuxer.start();
                        }
                        FlyLog.d("send format");
                        break;
                    default:
                        FlyLog.v("VideoSenderThread,MediaCode,eobIndex=" + eobIndex);
                        if (startTime == 0) {
                            startTime = mBufferInfo.presentationTimeUs / 1000;
                        }
                        /**
                         * we send sps pps already in INFO_OUTPUT_FORMAT_CHANGED
                         * so we ignore MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                         */
                        if (mBufferInfo.size != 0) {
                            ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
                            ByteBuffer outputBuffer = outputBuffers[eobIndex];
                            outputBuffer.position(mBufferInfo.offset);
                            outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);

                            outputBuffer.mark();
                            byte index[] = new byte[8];
                            outputBuffer.get(index, 0, index.length);
                            outputBuffer.reset();

                            FlyLog.d("H264 I:%s", ByteUtil.bytes2String(index));
//                        sendRealData((mBufferInfo.presentationTimeUs / 1000) - startTime, realData);
                            if (!isStop.get()) {
                                long time = System.currentTimeMillis();
                                if (time - recordStartTime > 60000 && index[4] == 0x65) {
                                    FlyLog.d("create new file");
                                    recordStartTime = time;
                                    mediaMuxer.stop();
                                    mediaMuxer.release();
                                    mediaMuxer = null;
                                    initMediaMuxer();
                                    videoTrackIndex = mediaMuxer.addTrack(mediaCodec.getOutputFormat());
                                    mediaMuxer.start();
                                }
                                mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, mBufferInfo);
                            }
                            FlyLog.d("send buffer");
                        }
                        mediaCodec.releaseOutputBuffer(eobIndex, false);
                        break;
                }
            }
            mediaMuxer.stop();
            mediaMuxer.release();
            mediaMuxer = null;
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
            mVirtualDisplay.release();
            mVirtualDisplay = null;
            mMediaProjection.stop();
            isRunning.set(false);
        }
    };

    public static ScreenRecorder getInstance() {
        return ScreenRecorderHolder.sInstance;
    }

    private static class ScreenRecorderHolder {
        public static final ScreenRecorder sInstance = new ScreenRecorder();
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public void start(MediaProjection mediaProjection) {
        isStop.set(false);
        mMediaProjection = mediaProjection;
        initMediaCodec();
        createVirtualDisplay();
        tHandler.post(handleTask);
    }

    private void initMediaCodec() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        FlyLog.d("created video format: " + format);
        try {
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mSurface = mediaCodec.createInputSurface();
            FlyLog.d("created input surface: " + mSurface);
            mediaCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private long recordStartTime = 0;

    private void initMediaMuxer() {
        try {
            if (mediaMuxer == null) {
                recordStartTime = System.currentTimeMillis();
                mediaMuxer = new MediaMuxer("/sdcard/" + System.currentTimeMillis() + ".mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createVirtualDisplay() {
        if (mVirtualDisplay == null) {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("SCREEN", mWidth, mHeight, 1,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mSurface, null, null);
        }
    }

    public void stop() {
        tHandler.removeCallbacksAndMessages(null);
        isStop.set(true);
    }
}
