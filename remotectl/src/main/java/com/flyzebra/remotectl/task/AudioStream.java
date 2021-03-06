package com.flyzebra.remotectl.task;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import com.flyzebra.remotectl.model.FileSaveTask;
import com.flyzebra.remotectl.model.FlvRtmpClient;
import com.flyzebra.utils.FlyLog;

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
    private AtomicBoolean isQuit = new AtomicBoolean(false);
    private AudioRecord mAudioRecord;
    private int recordBufSize = 0; // 声明recoordBufffer的大小字段
    private byte[] audioBuffer;
    private MediaCodec mAudioEncoder;
    private MediaFormat dstAudioFormat;
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

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
            FlyLog.d("record audio task start!");
            while (!isQuit.get()) {
                int size = mAudioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (size > 0) {
                    long nowTimeMs = SystemClock.uptimeMillis();
                    int eibIndex = mAudioEncoder.dequeueInputBuffer(-1);
                    if (eibIndex >= 0) {
                        ByteBuffer dstAudioEncoderIBuffer = mAudioEncoder.getInputBuffers()[eibIndex];
                        dstAudioEncoderIBuffer.position(0);
                        dstAudioEncoderIBuffer.put(audioBuffer, 0, audioBuffer.length);
                        mAudioEncoder.queueInputBuffer(eibIndex, 0, audioBuffer.length, nowTimeMs * 1000, 0);
                    } else {
                        FlyLog.d("dstAudioEncoder.dequeueInputBuffer(-1)<0");
                    }
                    FlyLog.d("AudioFilterHandler,ProcessTime:" + (System.currentTimeMillis() - nowTimeMs));
                }
            }
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
            FlyLog.d("record audio task end!");
        }
    };

    private Runnable runSendTask = new Runnable() {
        @Override
        public void run() {
            FlyLog.d("send audio task start!");
            while (!isQuit.get()) {
                FlvRtmpClient.getInstance().open();
                int eobIndex = mAudioEncoder.dequeueOutputBuffer(mBufferInfo, WAIT_TIME);
                switch (eobIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d(TAG, "AudioSenderThread,MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED");
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        FlvRtmpClient.getInstance().sendAudioSPS(mAudioEncoder.getOutputFormat());
                        FileSaveTask.getInstance().open(FileSaveTask.OPEN_AUDIO, mAudioEncoder.getOutputFormat());
                        break;
                    default:
                        Log.d(TAG, "AudioSenderThread,MediaCode,eobIndex=" + eobIndex);
                        if (startTime == 0) {
                            startTime = (int) (mBufferInfo.presentationTimeUs / 1000);
                        }
                        if (mBufferInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG && mBufferInfo.size != 0) {
                            ByteBuffer outputBuffer = mAudioEncoder.getOutputBuffers()[eobIndex];
                            outputBuffer.position(mBufferInfo.offset);
                            outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                            FlvRtmpClient.getInstance().sendAudioFrame(outputBuffer, (int) (mBufferInfo.presentationTimeUs / 1000));
                            mBufferInfo.presentationTimeUs = getPTSUs();
                            FileSaveTask.getInstance().writeAudioTrack(outputBuffer, mBufferInfo);
                            prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                        }
                        mAudioEncoder.releaseOutputBuffer(eobIndex, false);
                        break;
                }
            }
            FlyLog.d("send audio task end!");
        }
    };

    private long prevOutputPTSUs;
    private long getPTSUs() {
        long result = System.nanoTime()/1000L;
        if (result < prevOutputPTSUs)
            result = (prevOutputPTSUs - result) + result;
        return result;
    }


    public void start() {
        isQuit.set(false);
        initAudioEncoder();
        initAudioRecord();
        tHandler.post(runPutTask);
        sHandler.post(runSendTask);
    }

    private void initAudioRecord() {
        if (mAudioRecord == null) {
            recordBufSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioBuffer = new byte[recordBufSize];
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufSize);
            mAudioRecord.startRecording();
        }
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
        isQuit.set(true);
        tHandler.removeCallbacksAndMessages(null);
        sHandler.removeCallbacksAndMessages(null);
    }

}
