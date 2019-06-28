package com.flyzebra.record.task;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;

import com.flyzebra.record.bean.RtmpData;
import com.flyzebra.record.utils.FlyLog;
import com.flyzebra.record.utils.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Author FlyZebra
 * 2019/6/20 10:26
 * Describ:
 **/
public class SaveFileTask {
    public static final int OPEN_VIDEO = 1;
    public static final int OPEN_AUDIO = 2;
    private long ONE_RECORD_TIME = 60000;
    private static final int OUT_FORMAT = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    private static final String SAVA_PATH = "/sdcard/flyrecord";
    private static final String FILE_FORMAT = "yyyyMMdd_HHmmss";
    private MediaMuxer mediaMuxer;
    private AtomicBoolean isStartMediaMuxer = new AtomicBoolean(false);
    private AtomicBoolean isAddVideoTrack = new AtomicBoolean(false);
    private AtomicBoolean isAddAudioTrack = new AtomicBoolean(false);
    private boolean isRecordAudio = false;
    private int videoTrack;
    private int audioTrack;
    private MediaFormat mVideoMediaFormat;
    private MediaFormat mAudioMediaFormat;
    private long lastRecordTime = 0;
    private static final int MAX_QUEUE_CAPACITY = 100;
    private LinkedBlockingDeque<RtmpData> fileQuque = new LinkedBlockingDeque<>(MAX_QUEUE_CAPACITY);

    private static final HandlerThread sWorkerThread = new HandlerThread("save-file");

    static {
        sWorkerThread.start();
    }

    private static final Handler tHandler = new Handler(sWorkerThread.getLooper());

    public static SaveFileTask getInstance() {
        return SaveRecordFileHolder.sInstance;
    }

    private static class SaveRecordFileHolder {
        public static final SaveFileTask sInstance = new SaveFileTask();
    }

    public Runnable runTask = new Runnable() {
        @Override
        public void run() {
            while (isAddVideoTrack.get()) {
                if (fileQuque.size() > 0) {
                }
            }
        }
    };


    /**
     * 创建文件
     */
    private void initMediaMuxer() {
        try {
            lastRecordTime = System.currentTimeMillis() / ONE_RECORD_TIME;
            File file = new File(SAVA_PATH);
            if (!file.exists()) {
                file.mkdirs();
            }
            String fileName = SAVA_PATH + File.separator + TimeUtil.getCurrentTime(FILE_FORMAT) + ".mp4";
            mediaMuxer = new MediaMuxer(fileName, OUT_FORMAT);
            FlyLog.d("create new file: %s", fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    public void open(int type, MediaFormat format) {
        if (!isRecordAudio) {
            isAddAudioTrack.set(true);
        }
        if (mediaMuxer == null) {
            initMediaMuxer();
        }
        if (type == OPEN_VIDEO) {
            addVideoTrack(format);
        } else if (type == OPEN_AUDIO) {
            addAudioTrack(format);
        }
    }


    private void addVideoTrack(final MediaFormat format) {
        if (!isAddVideoTrack.get() && format != null) {
            mVideoMediaFormat = format;
            videoTrack = mediaMuxer.addTrack(format);
            isAddVideoTrack.set(true);
            startMediaMuxer();
        }

    }

    private void addAudioTrack(final MediaFormat format) {
        if (!isAddAudioTrack.get() && format != null) {
            mAudioMediaFormat = format;
            audioTrack = mediaMuxer.addTrack(format);
            isAddAudioTrack.set(true);
            startMediaMuxer();
        }

    }


    private void startMediaMuxer() {
        if (!isStartMediaMuxer.get() && isAddAudioTrack.get() && isAddVideoTrack.get()) {
            mediaMuxer.start();
            isStartMediaMuxer.set(true);
        }
    }


    public void writeVideoTrack(final ByteBuffer outputBuffer, final MediaCodec.BufferInfo mBufferInfo) {
        write(videoTrack, outputBuffer, mBufferInfo);
    }

    public void writeAudioTrack(final ByteBuffer outputBuffer, final MediaCodec.BufferInfo mBufferInfo) {
        write(audioTrack, outputBuffer, mBufferInfo);
    }

    public void write(int videoTrack, final ByteBuffer outputBuffer, final MediaCodec.BufferInfo mBufferInfo) {
        if (mediaMuxer != null) {
            //获取帧类型
            outputBuffer.mark();
            Byte type = outputBuffer.get(4);
            int frameType = type & 0x1F;
            outputBuffer.reset();

            long time = System.currentTimeMillis();
            if (time / ONE_RECORD_TIME - lastRecordTime > 0 && frameType == 5) {
                close();
                initMediaMuxer();
                addVideoTrack(mVideoMediaFormat);
                addAudioTrack(mAudioMediaFormat);
            }
            mediaMuxer.writeSampleData(videoTrack, outputBuffer, mBufferInfo);
        }

    }

    public void close() {
        isAddVideoTrack.set(false);
        isAddAudioTrack.set(false);
        isStartMediaMuxer.set(false);
        if (mediaMuxer != null) {
            mediaMuxer.stop();
            mediaMuxer.release();
        }
        mediaMuxer = null;
    }
}