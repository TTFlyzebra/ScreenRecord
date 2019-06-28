package com.flyzebra.record.task;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.content.ContentValues.TAG;

/**
 * Author FlyZebra
 * 2019/6/18 16:12
 * Describ:
 **/
public class AudioStream {
    private static final long WAIT_TIME = 5000;//1ms;
    private boolean shouldQuit = false;
    private AudioRecord mAudioRecord;
    private int recordBufSize = 0; // 声明recoordBufffer的大小字段
    private byte[] audioBuffer;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private MediaCodec mAudioEncoder;
    private MediaFormat dstAudioFormat;
    private MediaCodec.BufferInfo eInfo = new MediaCodec.BufferInfo();

    private static final HandlerThread sWorkerThread = new HandlerThread("encode-audio");

    static {
        sWorkerThread.start();
    }

    private static final Handler tHandler = new Handler(sWorkerThread.getLooper());

    private static final HandlerThread tWorkerThread = new HandlerThread("send-audio");

    static {
        tWorkerThread.start();
    }

    private static final Handler sHandler = new Handler(tWorkerThread.getLooper());
    private int startTime;

    public static AudioStream getInstance() {
        return AudioStreamHolder.sInstance;
    }

    private static class AudioStreamHolder {
        public static final AudioStream sInstance = new AudioStream();
    }

    private Runnable runPutTask = new Runnable() {
        @Override
        public void run() {
            while (isRunning.get()) {
                int size = mAudioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (size > 0) {
                }
            }
        }
    };

    private Runnable runSendTask = new Runnable() {
        @Override
        public void run() {
            while (isRunning.get()) {
                int eobIndex = mAudioEncoder.dequeueOutputBuffer(eInfo, WAIT_TIME);
                switch (eobIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d(TAG, "AudioSenderThread,MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED");
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
//                        LogTools.d("AudioSenderThread,MediaCodec.INFO_TRY_AGAIN_LATER");
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.d(TAG, "AudioSenderThread,MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:" +
                                mAudioEncoder.getOutputFormat().toString());
                        ByteBuffer csd0 = mAudioEncoder.getOutputFormat().getByteBuffer("csd-0");
//                        sendAudioSpecificConfig(0, csd0);
                        break;
                    default:
                        Log.d(TAG, "AudioSenderThread,MediaCode,eobIndex=" + eobIndex);
                        if (startTime == 0) {
                            startTime = (int) (eInfo.presentationTimeUs / 1000);
                        }
                        /**
                         * we send audio SpecificConfig already in INFO_OUTPUT_FORMAT_CHANGED
                         * so we ignore MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                         */
                        if (eInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG && eInfo.size != 0) {
                            ByteBuffer realData = mAudioEncoder.getOutputBuffers()[eobIndex];
                            realData.position(eInfo.offset);
                            realData.limit(eInfo.offset + eInfo.size);
//                            sendRealData((eInfo.presentationTimeUs / 1000) - startTime, realData);
                        }
                        mAudioEncoder.releaseOutputBuffer(eobIndex, false);
                        break;
                }
            }
            eInfo = null;
        }
    };


    public void start() {
        initAudioRecord();
        initAudioEncoder();
        tHandler.post(runPutTask);
        sHandler.post(runSendTask);
    }

    private void initAudioRecord() {
        recordBufSize = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        audioBuffer = new byte[recordBufSize];
        mAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufSize);
        mAudioRecord.startRecording();
        isRunning.set(true);
    }

    private void initAudioEncoder() {
        if (mAudioEncoder == null) {
            dstAudioFormat = new MediaFormat();
            dstAudioFormat.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
            dstAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            dstAudioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
            dstAudioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            dstAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 32 * 1024);
            dstAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8820);
            try {
                mAudioEncoder = MediaCodec.createEncoderByType(dstAudioFormat.getString(MediaFormat.KEY_MIME));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mAudioEncoder.configure(dstAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
    }


    public void stop() {
        mAudioRecord.stop();
        mAudioEncoder.stop();
        shouldQuit = true;
        tHandler.removeCallbacksAndMessages(null);
        sHandler.removeCallbacksAndMessages(null);
    }

}
