/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.extractor.ts;

import static java.lang.Math.min;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.audio.DtsUtil;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.ts.TsPayloadReader.TrackIdGenerator;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.ParsableBitArray;
import com.google.android.exoplayer2.util.ParsableByteArray;
import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import android.util.Log;

/**
 * Parses a continuous DTS byte stream and extracts individual samples.
 */
public final class DtsReader implements ElementaryStreamReader {

  private static final String TAG = "DtsReader";

  private static final int STATE_FINDING_FIRST_SYNC = 0;
  private static final int STATE_FINDING_SUBSEQUENT_SYNC = 1;
  private static final int STATE_CHECKING_EXSS_HEADER = 2;
  private static final int STATE_READING_EXSS = 3;
  private static final int STATE_COPYING_FRAME = 4;


  private static final int DTSHD_PARSER_SYNC_TYPE_STANDALONE_CORE = 0;
  private static final int DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS = 1;
  private static final int DTSHD_PARSER_SYNC_TYPE_CORE_PLUS_EXSS = 2;

  private static final int SYNC_WORD_SIZE = 4;

  private final ParsableByteArray syncWordBytes;

  private String formatId;
  private TrackOutput output;

  private int state;
  private int bytesRead;

  private Format mFormat;
  private int frameSize = 0;
  private int sampleRate = 48000;//Default sr
  private int numChannel = 6; //default num of channels
  private int bitDepth = 2; //default 16 bits = 2 bytes
  // Used to find the header.
  private int shift_register = 0;
  private int sync_word = 0;
  private int sync_type = 0;
  // Used when parsing the header.
  private ByteBuffer dtsFrameHolder;
  private ParsableBitArray dtsExSSBitstream;
  private final String language;
  private int syncBytesConsumed = 0;
  private int[] exss_ids = {0, 0, 0, 0};
  int exssCount = 0;
  int inSync = 0;

  private byte[] frameHolder = null;

  private static ByteBuffer parsableBytes = ByteBuffer.allocateDirect(36);

  // Used when reading the samples.
  private long timeUs;
  private long frameDurationUs;

  private static final int[] nRefClockTable = {32000, 44100, 48000, 0x7FFFFFFF};

  /**
   * Constructs a new reader for DTS elementary streams.
   *
   * @param language Track language.
   */
  public DtsReader(String language) {
    syncWordBytes = new ParsableByteArray(new byte[SYNC_WORD_SIZE]);
    state = STATE_FINDING_FIRST_SYNC;
    shift_register = 0;
    sync_word = 0;
    frameSize = 0;
    syncBytesConsumed = 0;
    exssCount = 0;
    inSync = 0;
    this.language = language;
    dtsFrameHolder = ByteBuffer.allocateDirect(DtsUtil.DTSAUDIO_MAX_FRAME_SIZE);
    frameHolder = new byte[DtsUtil.DTSAUDIO_MAX_FRAME_SIZE];
    dtsExSSBitstream = new ParsableBitArray(new byte[6]);
  }

  @Override
  public void seek() {
    state = STATE_FINDING_FIRST_SYNC;
    bytesRead = 0;
    shift_register = 0;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_FIRST_SYNC:
          if (skipToNextSync(data)) {
            if (sync_type == DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS) {
              state = STATE_CHECKING_EXSS_HEADER;
            } else {
              state = STATE_FINDING_SUBSEQUENT_SYNC;
            }
            /* Storing first sunc word to frame buffer */
            dtsFrameHolder.clear();
            dtsFrameHolder.put((byte) ((shift_register >> 24) & 0xFF));
            dtsFrameHolder.put((byte) ((shift_register >> 16) & 0xFF));
            dtsFrameHolder.put((byte) ((shift_register >> 8) & 0xFF));
            dtsFrameHolder.put((byte) ((shift_register) & 0xFF));

            syncBytesConsumed = 0;
            exssCount = 0;
            inSync = 0;
            dtsExSSBitstream.setPosition(0);
          }
          break;
        case STATE_FINDING_SUBSEQUENT_SYNC:
          if (findSubsequentSync(data, dtsFrameHolder)) {
            if (sync_type == (DTSHD_PARSER_SYNC_TYPE_CORE_PLUS_EXSS) || (sync_type
                == DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS)) {
              state = STATE_CHECKING_EXSS_HEADER;
            } else if (sync_type == DTSHD_PARSER_SYNC_TYPE_STANDALONE_CORE) {
              state = STATE_COPYING_FRAME;
            }
          } else {
            if (dtsFrameHolder.position()
                > DtsUtil.DTSAUDIO_MAX_FRAME_SIZE) { //The maximum DTS frame size
              Log.e(TAG, "Frame buffer can't hold frame size more than - "
                  + DtsUtil.DTSAUDIO_MAX_FRAME_SIZE);
              state = STATE_FINDING_FIRST_SYNC;
              shift_register = 0;
              sync_word = 0;
              frameSize = 0;
              syncBytesConsumed = 0;
              inSync = 0;
              dtsFrameHolder.clear();
            }
          }
          break;
        case STATE_CHECKING_EXSS_HEADER:
          if (parseExSSHeader(data, dtsFrameHolder, dtsExSSBitstream)) {
            state = STATE_READING_EXSS;
            if (exssCount > 4) {
              Log.e(TAG, "Exceeded max limit of ExtensionSS count");
              state = STATE_FINDING_FIRST_SYNC;
              shift_register = 0;
              sync_word = 0;
              frameSize = 0;
              exssCount = 0;
              syncBytesConsumed = 0;
              dtsExSSBitstream.setPosition(0);
              dtsFrameHolder.clear();
            }
          }
          break;
        case STATE_READING_EXSS:
          if (readExSS(data, dtsFrameHolder)) {
            if (state == STATE_FINDING_SUBSEQUENT_SYNC) {
              sync_word = shift_register; /* storing current sync as first sync word */
              dtsFrameHolder.clear();
              dtsFrameHolder.put((byte) ((shift_register >> 24) & 0xFF));
              dtsFrameHolder.put((byte) ((shift_register >> 16) & 0xFF));
              dtsFrameHolder.put((byte) ((shift_register >> 8) & 0xFF));
              dtsFrameHolder.put((byte) ((shift_register) & 0xFF));
            }
          }
          break;
        case STATE_COPYING_FRAME:
          if ((sync_type == DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS)) {
            frameSize = dtsFrameHolder.position() - (4
                + 6);// Current position of Frame buffer - (size of next sync word read from input bitstream + ExSS header size read for extracting EsSS index)
          } else {
            frameSize = dtsFrameHolder.position()
                - 4;// Current position of Frame buffer - (size of next sync word read from input bitstream)
          }

          Log.d(TAG, "DTS Frame Size is " + frameSize);

          /* For better performance, parsing frame only during first frame synchronization */
          if (inSync == 0) {
            dtsFrameHolder.flip(); /* set current position as limit and set position to zero */
            boolean rc = parseFrame(dtsFrameHolder, frameSize);

            if (rc == true) {
              inSync = 1;
            } else {
              Log.e(TAG, "Error in parsing frame");

              /* Copy second sync word to Frame buffer */
              dtsFrameHolder.clear();
              dtsFrameHolder.put((byte) ((shift_register >> 24) & 0xFF));
              dtsFrameHolder.put((byte) ((shift_register >> 16) & 0xFF));
              dtsFrameHolder.put((byte) ((shift_register >> 8) & 0xFF));
              dtsFrameHolder.put((byte) ((shift_register) & 0xFF));

              syncBytesConsumed = 0;
              exssCount = 0;
              dtsExSSBitstream.setPosition(0);

              if ((shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) ||
                  (shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_LE)) {
                sync_type = DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS;
                /* Copy bytes stored for ExSS header parsing */
                dtsFrameHolder.put(dtsExSSBitstream.data[0]);
                dtsFrameHolder.put(dtsExSSBitstream.data[1]);
                dtsFrameHolder.put(dtsExSSBitstream.data[2]);
                dtsFrameHolder.put(dtsExSSBitstream.data[3]);
                dtsFrameHolder.put(dtsExSSBitstream.data[4]);
                dtsFrameHolder.put(dtsExSSBitstream.data[5]);
              }

              sync_word = shift_register; /* storing current sync as first sync word */
              state = STATE_FINDING_SUBSEQUENT_SYNC;

              break;
            }
          }

          dtsFrameHolder.flip(); /* set current position as limit and set position to zero */
          dtsFrameHolder.get(frameHolder, 0, frameSize);
          output.sampleData(new ParsableByteArray(frameHolder), frameSize);
          output.sampleMetadata(timeUs, C.SAMPLE_FLAG_SYNC, frameSize, 0, null);

          frameDurationUs = DtsUtil.getFrameDuration();
          Log.d(TAG, "DTS frameDuration in micro seconds " + frameDurationUs);
          timeUs += frameDurationUs;

          /* Copy second sync word to Frame buffer */
          dtsFrameHolder.clear();
          dtsFrameHolder.put((byte) ((shift_register >> 24) & 0xFF));
          dtsFrameHolder.put((byte) ((shift_register >> 16) & 0xFF));
          dtsFrameHolder.put((byte) ((shift_register >> 8) & 0xFF));
          dtsFrameHolder.put((byte) ((shift_register) & 0xFF));

          if ((sync_type == DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS)) {
            /* Copy bytes stored for ExSS header parsing */
            dtsFrameHolder.put(dtsExSSBitstream.data[0]);
            dtsFrameHolder.put(dtsExSSBitstream.data[1]);
            dtsFrameHolder.put(dtsExSSBitstream.data[2]);
            dtsFrameHolder.put(dtsExSSBitstream.data[3]);
            dtsFrameHolder.put(dtsExSSBitstream.data[4]);
            dtsFrameHolder.put(dtsExSSBitstream.data[5]);
          }

          sync_word = shift_register; /* storing current sync as first sync word */
          state = STATE_FINDING_SUBSEQUENT_SYNC;

          break;
      }
    }
  }

  @Override
  public void packetFinished() {
    // Do nothing.
  }


  /**
   * Locates the next SYNC value in the buffer, advancing the position to the byte that immediately
   * follows it. If SYNC was not located, the position is advanced to the limit.
   *
   * @param pesBuffer The buffer whose position should be advanced.
   * @return Whether SYNC was found.
   */
  private boolean skipToNextSync(ParsableByteArray pesBuffer) {
    int bytesReadB4Sync = 0;
    while (pesBuffer.bytesLeft() > 0) {
      shift_register = (shift_register << 8) | pesBuffer.readUnsignedByte();
      /* check sync word */
      bytesReadB4Sync++;

      if ((shift_register != DtsUtil.DTSHD_SYNC_CORE_16BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_16BIT_LE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_14BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_14BIT_LE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_EXSS_16BIT_LE)) {
        continue; /* continue while loop */
      }

      if ((shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) ||
          (shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_LE)) {
        sync_type = DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS;
        Log.d(TAG, "skipToNextSync: Sync Type is DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS");
      }

      if (sync_word == 0) {
        Log.d(TAG, "skipToNextSync: Sync Word = 0x" + Integer.toHexString(shift_register)
            + " found after reading " + bytesReadB4Sync + " from the buffer.");
        sync_word = shift_register;
      } else {
        Log.d(TAG, "skipToNextSync: Found Sync Frame - 0x" + Integer.toHexString(shift_register)
            + " after reading " + bytesReadB4Sync + " from the buffer.");
      }

      return true;
    }
    return false;
  }

  /**
   * Locates the second SYNC value in the buffer, advancing the position to the byte that immediately
   * follows it. If SYNC was not located, the position is advanced to the limit.
   *
   * @param pesBuffer The source from which to read.
   * @param target    The target into which data is to be read.
   * @return Whether the target length was reached.
   */
  private boolean findSubsequentSync(ParsableByteArray pesBuffer, ByteBuffer target) {

    while (pesBuffer.bytesLeft() > 0) {
      if (target.position()
          > DtsUtil.DTSAUDIO_MAX_FRAME_SIZE) { /* No space left on target frame buffer */
        Log.d(TAG,
            "findSubsequentSync: Frame buffer is full, current position is " + target.position());
        return false;
      }
      shift_register = (shift_register << 8);
      shift_register |= pesBuffer.readUnsignedByte();
      target.put((byte) (shift_register & 0xFF));
      syncBytesConsumed++;

      if ((shift_register != DtsUtil.DTSHD_SYNC_CORE_16BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_16BIT_LE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_14BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_14BIT_LE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_EXSS_16BIT_LE)) {
        continue; /* continue while loop */
      }

      if (((shift_register == DtsUtil.DTSHD_SYNC_CORE_16BIT_BE) && (sync_word
          == DtsUtil.DTSHD_SYNC_CORE_16BIT_BE)) ||
          ((shift_register == DtsUtil.DTSHD_SYNC_CORE_16BIT_LE) && (sync_word
              == DtsUtil.DTSHD_SYNC_CORE_16BIT_LE)) ||
          ((shift_register == DtsUtil.DTSHD_SYNC_CORE_14BIT_BE) && (sync_word
              == DtsUtil.DTSHD_SYNC_CORE_14BIT_BE)) ||
          ((shift_register == DtsUtil.DTSHD_SYNC_CORE_14BIT_LE) && (sync_word
              == DtsUtil.DTSHD_SYNC_CORE_14BIT_LE))) {
        sync_type = DTSHD_PARSER_SYNC_TYPE_STANDALONE_CORE;
        Log.d(TAG, "findSubsequentSync: Sync Type is DTSHD_PARSER_SYNC_TYPE_STANDALONE_CORE");
      }

      if (((shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) && (sync_word
          == DtsUtil.DTSHD_SYNC_CORE_16BIT_BE)) ||
          ((shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_LE) && ((sync_word
              == DtsUtil.DTSHD_SYNC_CORE_16BIT_LE)))) {
        sync_type = DTSHD_PARSER_SYNC_TYPE_CORE_PLUS_EXSS;
        Log.d(TAG, "findSubsequentSync: Sync Type is DTSHD_PARSER_SYNC_TYPE_CORE_PLUS_EXSS");
      }

      Log.d(TAG, "findSubsequentSync: Found Sync Frame - 0x" + Integer.toHexString(shift_register)
          + " after reading " + syncBytesConsumed + " from the buffer.");
      syncBytesConsumed = 0;

      return true;
    }
    return false;
  }

  /**
   * Parse Extension Sub-stream header and extract 'nExtSSIndex'
   *
   * @param pesBuffer        The source from which to read.
   * @param target           The target into which data is to be read.
   * @param dtsExSSBitstream Temporary bit-stream buffer.
   * @return Whether the target length was reached.
   */
  private boolean parseExSSHeader(ParsableByteArray pesBuffer, ByteBuffer target,
      ParsableBitArray dtsExSSBitstream) {

    byte b;

    while (pesBuffer.bytesLeft() > 0) {
      /* Copy next 6 bytes(48 bits) to extract 'nExtSSIndex' from ExSS header */
      b = (byte) (pesBuffer.readUnsignedByte() & 0xFF);
      target.put(b);
      dtsExSSBitstream.data[(dtsExSSBitstream.getPosition() / 8)] = b;
      syncBytesConsumed++;
      dtsExSSBitstream.setPosition(syncBytesConsumed * 8);

      /* If copying of first 6 bytes completed */
      if (syncBytesConsumed == 6) {
        dtsExSSBitstream.setPosition(0);
        if (shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) {
          dtsExSSBitstream.skipBits(8); /* UserDefinedBits */
          int nExtSSIndex = dtsExSSBitstream.readBits(2);
          exss_ids[exssCount] = nExtSSIndex;
          exssCount++;
        }
        dtsExSSBitstream.setPosition(0);
        syncBytesConsumed = 0;
        return true;
      }
    }
    return false;
  }

  /**
   * Continues a read from the provided {@code source} into a given {@code target}. It's assumed
   * that the data should be written into {@code target} starting from an offset of zero.
   *
   * @param pesBuffer The source from which to read.
   * @param target    The target into which data is to be read.
   * @return Whether the target length was reached.
   */
  private boolean readExSS(ParsableByteArray pesBuffer, ByteBuffer target) {

    if (sync_type == DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS) {
      if ((exssCount > 1) && (exss_ids[0] == exss_ids[exssCount - 1]) && (exssCount
          <= 4)) { /* Sub-sequent ExSS-IDs are equal. Found sync frame for statndalone ExSS */
        state = STATE_COPYING_FRAME;
        exssCount = 1;
        exss_ids[0] = exss_ids[exssCount - 1]; /* Store current ID to first index */
        exss_ids[1] = 0;
        exss_ids[2] = 0;
        exss_ids[3] = 0;

        syncBytesConsumed = 0;
        return true;
      }
    }

    while (pesBuffer.bytesLeft() > 0) {

      shift_register = (shift_register << 8);
      shift_register |= pesBuffer.readUnsignedByte();
      target.put((byte) (shift_register & 0xFF));
      syncBytesConsumed++;
      if ((shift_register != DtsUtil.DTSHD_SYNC_CORE_16BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_16BIT_LE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_14BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_CORE_14BIT_LE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) &&
          (shift_register != DtsUtil.DTSHD_SYNC_EXSS_16BIT_LE)) {
        continue;
      }

      if (sync_type == DTSHD_PARSER_SYNC_TYPE_CORE_PLUS_EXSS) {
        if ((shift_register == DtsUtil.DTSHD_SYNC_CORE_16BIT_BE) ||
            (shift_register == DtsUtil.DTSHD_SYNC_CORE_16BIT_LE) ||
            (shift_register == DtsUtil.DTSHD_SYNC_CORE_14BIT_BE) ||
            (shift_register
                == DtsUtil.DTSHD_SYNC_CORE_14BIT_LE)) { /* Found next Core sync word. Frame completed. */
          state = STATE_COPYING_FRAME;
          exssCount = 0;
          exss_ids[0] = 0;
          exss_ids[1] = 0;
          exss_ids[2] = 0;
          exss_ids[3] = 0;

        } else {
          state = STATE_CHECKING_EXSS_HEADER;
        }
      }

      if (sync_type == DTSHD_PARSER_SYNC_TYPE_STANDALONE_EXSS) {
        if ((shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_BE) ||
            (shift_register == DtsUtil.DTSHD_SYNC_EXSS_16BIT_LE)) {
          state = STATE_CHECKING_EXSS_HEADER;
        } else {
          Log.e(TAG, "Expected ExSS sync word but got Core sync word" + Integer.toHexString(
              shift_register));
          state = STATE_FINDING_SUBSEQUENT_SYNC;
        }
      }

      Log.d(TAG, "readExSS: Found Sync Frame - 0x" + Integer.toHexString(shift_register)
          + " after " + syncBytesConsumed + " bytes.");
      syncBytesConsumed = 0;
      return true;
    }
    return false;
  }

  /**
   * Parses the sample header.
   */
  private boolean parseFrame(ByteBuffer framedata, int frameSize) {

    if (DtsUtil.parseFrame(framedata, frameSize)) {
      sampleRate = DtsUtil.getSampleRate();
      numChannel = DtsUtil.getNumChannel();

      if (mFormat == null) {
        mFormat = DtsUtil.getFormat(mFormat.NO_VALUE, C.UNKNOWN_TIME_US);
        output.format(mFormat);
      }

      return true;
    }
    return false;

  }
}
