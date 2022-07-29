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
package com.google.android.exoplayer2.audio;

import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableBitArray;

import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.Util;
import java.nio.ByteBuffer;
import java.util.Arrays;

final class dtsParsableBitArray {

  public ParsableBitArray dtsStream;

  dtsParsableBitArray(byte[] myBytes) {
    dtsStream = new ParsableBitArray(myBytes);
  }

  dtsParsableBitArray(ParsableBitArray frameBits) {
    dtsStream = frameBits;
  }

  /* Add a method to clone a ParsableBitArray */
  public dtsParsableBitArray dtsBitstreamClone() {
    dtsParsableBitArray myArray;
    myArray = new dtsParsableBitArray(this.dtsStream.data);

    myArray.dtsStream.setPosition(this.dtsStream.getPosition());
    return myArray;
  }
}

/**
 * Utility methods for parsing DTS frames.
 */
public final class DtsUtil {

  /**
   * Maximum rate for a DTS audio stream, in bytes per second.
   *
   * <p>DTS allows an 'open' bitrate, but we assume the maximum listed value: 1536 kbit/s.
   */
  public static final int DTS_MAX_RATE_BYTES_PER_SECOND = 1536 * 1000 / 8;
  /**
   * Maximum rate for a DTS-HD audio stream, in bytes per second.
   */
  public static final int DTS_HD_MAX_RATE_BYTES_PER_SECOND = 18000 * 1000 / 8;

  private static final String TAG = "DtsUtil";
  public static final int DTSAUDIO_MAX_FRAME_SIZE = 32768;
  public static final int DTSHD_SYNC_CORE_16BIT_BE = 0x7FFE8001;
  public static final int DTSHD_SYNC_CORE_14BIT_BE = 0x1FFFE800;
  public static final int DTSHD_SYNC_CORE_16BIT_LE = 0xFE7F0180;
  public static final int DTSHD_SYNC_CORE_14BIT_LE = 0xFF1F00E8;
  public static final int DTSHD_SYNC_EXSS_16BIT_BE = 0x64582025;
  public static final int DTSHD_SYNC_EXSS_16BIT_LE = 0x58642520;

  private static final byte FIRST_BYTE_16B_BE = (byte) (DTSHD_SYNC_CORE_16BIT_BE >>> 24);
  private static final byte FIRST_BYTE_14B_BE = (byte) (DTSHD_SYNC_CORE_14BIT_BE >>> 24);
  private static final byte FIRST_BYTE_16B_LE = (byte) (DTSHD_SYNC_CORE_16BIT_LE >>> 24);
  private static final byte FIRST_BYTE_14B_LE = (byte) (DTSHD_SYNC_CORE_14BIT_LE >>> 24);
  private static final byte FIRST_BYTE_EXSS_16BIT_LE = (byte) (DTSHD_SYNC_EXSS_16BIT_LE >>> 24);

  private static final int[] dtsChannelCountTable = new int[]{1, 2, 2, 2, 2, 3, 3, 4, 4, 5};
  private static final int[] nRefClockTable = {32000, 44100, 48000, 0x7FFFFFFF};
  private static final int[] dtsSamplingRateTable = new int[]{0, 8000, 16000, 32000, 0,
      0, 11025, 22050, 44100, 0,
      0, 12000, 24000, 48000, 0, 0};
  private static final int[] dtsSampleRateTableExtension = new int[]{8000, 16000, 32000, 64000,
      128000,
      22050, 44100, 88200, 176400, 352800,
      12000, 24000, 48000, 96000, 192000, 384000};

  private static int dtsChannelCount = 2; //Default value
  private static int dtsSamplingRate = 48000; //Default value
  private static int dtsBitDepth = 2; //16bits
  private static int dtsNumOfSamples = 0;
  private static int dtsFrameSizeInBytes = 0;
  private static long dtsFrameDuration = 0;

  private static int sampleSize = 0;


  public static boolean parseFrame(ByteBuffer dataBuffer, int frameSize) {
    int samplingrate = 0;
    int numChannels = 0;

    byte[] dtsFrame = new byte[dataBuffer.limit()];
    dataBuffer.get(dtsFrame, 0, dataBuffer.limit());

    ParsableBitArray frameBits = getNormalizedFrameHeader(
        dtsFrame); //convert header to 16bit Big-endian if format is 14bit or Little-endian
    dtsParsableBitArray bitData = new dtsParsableBitArray(frameBits);
    int nBitstreamOffset = 0;
    int nShiftReg = 0;
    int nExSSsyncWord = 0;
    byte temp;
    int nCoreHits = 0;

    dtsFrameSizeInBytes = frameSize;

    while (nBitstreamOffset < frameSize) {

      nShiftReg = (nShiftReg << 8);
      nShiftReg |= (bitData.dtsStream.readBits(8) & 0x000000FF);
      nBitstreamOffset++;

      if (((nShiftReg == DTSHD_SYNC_CORE_16BIT_BE) ||
          (nShiftReg == DTSHD_SYNC_CORE_16BIT_LE) ||
          (nShiftReg == DTSHD_SYNC_CORE_14BIT_BE) ||
          (nShiftReg == DTSHD_SYNC_CORE_14BIT_LE)) &&
          ((nBitstreamOffset & 3) == 0)) {

        int NBLKS, FSIZE, AMODE, SFREQ, EXT_AUDIO_ID, EXT_AUDIO, LFF;

        nCoreHits++;
        if (nCoreHits > 1) {
          /* Core sync word has hit again; break from loop */
          break;
        }
        if (bitData.dtsStream.bitsLeft()
            < 55) { /* number of bits needed for parsing CoreSS header excluding sync bits*/
          Log.e(TAG, "parseFrame: Not enough bits left for further parsing");
          return false;
        }

        bitData.dtsStream.skipBits(7); //FTYPE(1) + SHORT(5) + CRC(1)

        NBLKS = bitData.dtsStream.readBits(7);
        Log.d(TAG, "parseFrame: Number of Samples(CoreSS) " + (NBLKS + 1) * 32);

        FSIZE = bitData.dtsStream.readBits(14)
            + 1; //Frame size doesn't really matter as we always read data between sync words
        Log.d(TAG, "parseFrame: Frame size(CoreSS) " + FSIZE);

        AMODE = bitData.dtsStream.readBits(6);
        SFREQ = bitData.dtsStream.readBits(4);
        bitData.dtsStream.skipBits(5 + 1 + 1 + 1 + 1 + 1 + 3 + 1
            + 1); /* RATE, FIXEDBIT, DYNF, TIMEF, AUXF, HDCD, EXT_AUDIO_ID, EXT_AUDIO, ASPF */
        LFF = bitData.dtsStream.readBits(2);

        numChannels = (AMODE <= 9) ? dtsChannelCountTable[AMODE] : 0;
        numChannels += (LFF != 0) ? 1 : 0;
        samplingrate = dtsSamplingRateTable[SFREQ];
        dtsNumOfSamples = ((NBLKS + 1) * 32);
        dtsFrameDuration = (long) ((float) (dtsNumOfSamples * C.MICROS_PER_SECOND) / samplingrate);

        nBitstreamOffset += (FSIZE - 4);
        if (nBitstreamOffset < frameSize) {
          bitData.dtsStream.setPosition(FSIZE * 8); /* Set bit-stream position after Core frame*/
        }

      } else if (((nShiftReg == DTSHD_SYNC_EXSS_16BIT_BE) ||
          (nShiftReg == DTSHD_SYNC_EXSS_16BIT_LE)) &&
          ((nBitstreamOffset & 3) == 0)) {

        int nExtSSIndex, nuActiveAssetMask, nuNumAudioPresnt, nuNumAssets, numExtHeadersize, numExtSSFsize;
        int nuExSSFrameDurationCode = 0, nuRefClockCode = 0;

        if (nExSSsyncWord == nShiftReg) {
          /* If same ExSS sync word has occured again, break from the loop. */
          /* Not handled streams with multiple ExSS */
          break;
        }
        nExSSsyncWord = nShiftReg;

        if (bitData.dtsStream.bitsLeft() < 11) { /* to parse up to 'bHeaderSizeType' */
          Log.e(TAG, "parseFrame: Not enough bits left for further parsing");
          return false;
        }

        bitData.dtsStream.skipBits(8); //UserDefinedBits(8)
        nExtSSIndex = bitData.dtsStream.readBits(2);

        int bHeaderSizeType = bitData.dtsStream.readBits(1);
        int numBits4Header = 0;
        int numBits4ExSSFsize = 0;

        if (bHeaderSizeType == 0) {
          numBits4Header = 8;
          numBits4ExSSFsize = 16;
        } else {
          numBits4Header = 12;
          numBits4ExSSFsize = 20;
        }

        if (bitData.dtsStream.bitsLeft() < numBits4Header + numBits4ExSSFsize) {
          Log.e(TAG, "parseFrame: Not enough bits left for further parsing");
          return false;
        }

        numExtHeadersize = bitData.dtsStream.readBits(numBits4Header) + 1;
        numExtSSFsize = bitData.dtsStream.readBits(numBits4ExSSFsize) + 1;

        if (bitData.dtsStream.bitsLeft() < (numExtHeadersize * 8) - (numBits4Header
            + numBits4ExSSFsize + 11 + 32 /*sync bits*/)) {
          Log.e(TAG, "parseFrame: Not enough bits left for further parsing");
          return false;
        }

        Log.d(TAG, "parseFrame: Frame size(ExSS) " + numExtSSFsize);

        int bStaticFieldsPresent = bitData.dtsStream.readBits(1);

        if (bStaticFieldsPresent > 0) {
          nuRefClockCode = bitData.dtsStream.readBits(2);
          nuExSSFrameDurationCode = 512 * (bitData.dtsStream.readBits(3) + 1);

          if (bitData.dtsStream.readBits(1) > 0) /* bTimeStampFlag */ {
            bitData.dtsStream.readBits(32 + 4);
          }
          nuNumAudioPresnt = bitData.dtsStream.readBits(3) + 1;
          nuNumAssets = bitData.dtsStream.readBits(3) + 1;

          int[] nuActiveExSSMask = new int[8];
          for (int nAuPr = 0; nAuPr < nuNumAudioPresnt; nAuPr++) {
            nuActiveExSSMask[nAuPr] = bitData.dtsStream.readBits(nExtSSIndex + 1);
          }

          for (int nAuPr = 0; nAuPr < nuNumAudioPresnt; nAuPr++) {
            for (int nSS = 0; nSS < nExtSSIndex + 1; nSS++) {
              if (((nuActiveExSSMask[nAuPr] >> nSS) & 0x1) == 1) {
                nuActiveAssetMask = bitData.dtsStream.readBits(8);
              } else {
                nuActiveAssetMask = 0;
              }
            }
          }

          int bMixMetaDataEnbl = bitData.dtsStream.readBits(1);
          if (bMixMetaDataEnbl == 1) /* bMixMetadataEnbl */ {
            bitData.dtsStream.skipBits(2); /* nuMixMetadataAdjLevel */
            int nuBits4MixOutMask = (bitData.dtsStream.readBits(2) + 1) << 2;
            int nuNumMixOutConfigs = bitData.dtsStream.readBits(2) + 1;

            /* Output Mixing Configuration Loop  */
            for (int nso = 0; nso < nuNumMixOutConfigs; nso++) {
              bitData.dtsStream.skipBits(nuBits4MixOutMask); /* nuMixOutChMask */
            }
          }
        } else {
          nuNumAudioPresnt = 1;
          nuNumAssets = 1;
        }

        for (int nAst = 0; nAst < nuNumAssets; nAst++) {
          bitData.dtsStream.skipBits(numBits4ExSSFsize);
        }

        int nuAssetDescriptFsize = 0;
        /* Asset descriptor */
        for (int nAst = 0; nAst < nuNumAssets; nAst++) {

          nuAssetDescriptFsize = bitData.dtsStream.readBits(9) + 1;
          int nuAssetIndex = bitData.dtsStream.readBits(3);

          /* asset is active, parse asset descriptor further */
          if (bStaticFieldsPresent == 1) {
            if (bitData.dtsStream.readBits(1) == 1) /* bAssetTypeDescrPresent */ {
              bitData.dtsStream.skipBits(4); /* nuAssetTypeDescriptor */
            }
            if (bitData.dtsStream.readBits(1) == 1) /* bLanguageDescrPresent */ {
              bitData.dtsStream.skipBits(24); /* LanguageDescriptor */
            }
            if (bitData.dtsStream.readBits(1) == 1) /* bInfoTextPresent */ {
              int nuInfoTextByteSize = bitData.dtsStream.readBits(10) + 1;
              bitData.dtsStream.skipBits(nuInfoTextByteSize * 8); /* InfoTextString */
            }
            bitData.dtsStream.skipBits(5); /* nuBitResolution */
            samplingrate = dtsSampleRateTableExtension[bitData.dtsStream.readBits(4)];
            numChannels = bitData.dtsStream.readBits(8) + 1;
          } else {
            /* dummy values */
            samplingrate = 48000;
            numChannels = 8;
          }
        }

        dtsNumOfSamples = (int) ((float) nuExSSFrameDurationCode * (samplingrate
            / nRefClockTable[nuRefClockCode]));
        dtsFrameDuration = (long) (((float) dtsNumOfSamples * C.MICROS_PER_SECOND)
            / samplingrate); // In seconds

        nBitstreamOffset += numExtSSFsize - 4; /* Setting bit-stream position at the end of ExSS*/
        /* Not handled  bitData.dtsStream.setPosition(). Cases with multiple EXSS in a frame needs to be handled */
      }
    }

    if (numChannels == 0 || (numChannels > 2 && numChannels < 6)) {
      numChannels = 6; //[NJ] Give a default value to try
    } else if (numChannels > 6 && numChannels != 8) {
      numChannels = 8;
    }

    if (samplingrate == 0) {
      samplingrate = 48000;
    }

    dtsChannelCount = numChannels;
    dtsSamplingRate = samplingrate;

    Log.d(TAG, "parseFrame: Number of Channels " + numChannels);
    Log.d(TAG, "parseFrame: Sampling Frequency " + samplingrate);
    Log.d(TAG, "parseFrame: Frame duration in micro seconds " + dtsFrameDuration);

    return true;
  }

  public static Format getFormat(int trackId, long durationUs) {

    Format dtsFormat;

    dtsFormat = Format.createAudioSampleFormat(((Integer) trackId).toString(), MimeTypes.AUDIO_DTS,
        "dtsc",
        Format.NO_VALUE, DTSAUDIO_MAX_FRAME_SIZE,
        dtsChannelCount, dtsSamplingRate, null, null, Format.NO_VALUE, null);

    return dtsFormat;
  }

  public static int getFrameSize() {
    return dtsFrameSizeInBytes;
  }

  public static void setFrameSize(int newFS) {
    if (newFS != dtsFrameSizeInBytes && newFS > 0) {
      dtsFrameSizeInBytes = newFS;
    }
  }

  public static int getNumOfAudioSamples() {
    return dtsNumOfSamples;
  }

  public static void setNumOfAudioSamples(int newFS) {
    if (newFS != dtsNumOfSamples && newFS > 0) {
      dtsNumOfSamples = newFS;
    }
  }

  public static int getSampleRate() {
    return dtsSamplingRate;
  }

  public static void setSampleRate(int newSR) {
    if (newSR != dtsSamplingRate && newSR > 0 && newSR <= 48000) {
      dtsSamplingRate = newSR;
    }
  }

  public static int getNumChannel() {
    return dtsChannelCount;
  }

  public static void setNumChannel(int chn) {
    if (chn != dtsChannelCount && chn > 0 && chn <= 8) {
      dtsChannelCount = chn;
    }
  }

  public static int getBitDepth() {
    return dtsBitDepth;
  }

  public static void setDtsBitDepth(int bitDepth) {
    if (bitDepth != dtsBitDepth && bitDepth > 0 && bitDepth <= 4) {
      dtsBitDepth = bitDepth;
    }
  }

  public static long getFrameDuration() {
    return dtsFrameDuration;
  }

  /**
   * Returns the number of audio samples represented by the given DTS frame
   *
   * @param data The frame to parse.
   * @return The number of audio samples represented by the frame.
   */
  public static int parseDtsAudioSampleCount(ByteBuffer data) {

    int frameSize = 0;
    int sampleCount = 0;
    boolean rc;

    frameSize = data.limit();
    rc = parseFrame(data, frameSize);

    if (rc == true) {
      sampleCount = getNumOfAudioSamples();
    }

    Log.d(TAG, "parseDtsAudioSampleCount: Sample count " + sampleCount);
    return sampleCount;

  }

  private static ParsableBitArray getNormalizedFrameHeader(byte[] frameHeader) {
    if (frameHeader[0] == FIRST_BYTE_16B_BE) {
      // The frame is already 16-bit mode, big endian.
      return new ParsableBitArray(frameHeader);
    }
    // Data is not normalized, but we don't want to modify frameHeader.
    frameHeader = Arrays.copyOf(frameHeader, frameHeader.length);
    if (isLittleEndianFrameHeader(frameHeader)) {
      // Change endianness.
      for (int i = 0; i < frameHeader.length - 1; i += 2) {
        byte temp = frameHeader[i];
        frameHeader[i] = frameHeader[i + 1];
        frameHeader[i + 1] = temp;
      }
    }
    ParsableBitArray frameBits = new ParsableBitArray(frameHeader);
    if (frameHeader[0] == (byte) (DTSHD_SYNC_CORE_14BIT_BE >> 24)) {
      // Discard the 2 most significant bits of each 16 bit word.
      ParsableBitArray scratchBits = new ParsableBitArray(frameHeader);
      while (scratchBits.bitsLeft() >= 16) {
        scratchBits.skipBits(2);
        frameBits.putInt(scratchBits.readBits(14), 14);
      }
    }
    frameBits.reset(frameHeader);
    return frameBits;
  }

  private static boolean isLittleEndianFrameHeader(byte[] frameHeader) {
    return frameHeader[0] == FIRST_BYTE_16B_LE || frameHeader[0] == FIRST_BYTE_14B_LE ||
        frameHeader[0] == FIRST_BYTE_EXSS_16BIT_LE;
  }
}