/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Adapted from Jon Kristensen's JOgg/JOrbis tutorial <http://www.jcraft.com/jorbis/tutorial/Tutorial.html>.
 */

package com.four142.png2audio.player;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.InputStream;

public class OggPlayer implements Runnable {

    private Thread playerThread = new Thread(this);

    private InputStream inputStream = null;

    private byte[] buffer = null;
    private int bufferSize = 2048, count = 0, index = 0;

    private byte[] convertedBuffer = null;
    private int convertedBufferSize = 0;

    private SourceDataLine sourceDataLine = null;

    private float[][][] pcmInfo;
    private int[] pcmIndex;

    //<editor-fold desc="jogg Objects">
    private Packet joggPacket = new Packet();
    private Page joggPage = new Page();
    private StreamState joggStreamState = new StreamState();
    private SyncState joggSyncState = new SyncState();
    //</editor-fold>
    //<editor-fold desc="jorbis Objects">
    private DspState jorbisDspState = new DspState();
    private Block jorbisBlock = new Block(jorbisDspState);
    private Comment jorbisComment = new Comment();
    private Info jorbisInfo = new Info();
    //</editor-fold>

    public OggPlayer(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    private void initialize() {
        joggSyncState.init();
        joggSyncState.buffer(bufferSize);
        buffer = joggSyncState.data;
    }

    private boolean initializeSound() {
        convertedBufferSize = 2 * bufferSize;
        convertedBuffer = new byte[convertedBufferSize];

        jorbisDspState.synthesis_init(jorbisInfo);

        jorbisBlock.init(jorbisDspState);

        int channels = jorbisInfo.channels;
        int rate = jorbisInfo.rate;

        AudioFormat audioFormat = new AudioFormat((float) rate, 16, channels, true, false);
        DataLine.Info dataLineInfo = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);

        try {
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(dataLineInfo);
            sourceDataLine.open(audioFormat);
        } catch (LineUnavailableException e) {
            return false;
        }

        sourceDataLine.start();

        pcmInfo = new float[1][][];
        pcmIndex = new int[jorbisInfo.channels];

        return true;
    }

    private boolean readHeader() {
        boolean needMoreData = true;
        int packet = 1;

        while (needMoreData) {
            try {
                count = inputStream.read(buffer, index, bufferSize);
            } catch (IOException e) {
                // This should only happen if the audio data is invalid
                System.err.println("ERROR: unable to decode input file");
                System.exit(4);
            }
            joggSyncState.wrote(count);
            switch (packet) {
                case 1: {
                    switch (joggSyncState.pageout(joggPage)) {
                        case -1:
                            return false;
                        case 0:
                            break;
                        case 1: {
                            joggStreamState.init(joggPage.serialno());
                            joggStreamState.reset();

                            jorbisInfo.init();
                            jorbisComment.init();

                            if (joggStreamState.pagein(joggPage) == -1
                                    || joggStreamState.packetout(joggPacket) != 1
                                    || jorbisInfo.synthesis_headerin(jorbisComment, joggPacket) < 0)
                                return false;

                            packet++;
                            break;
                        }
                    }

                    if (packet == 1) break;
                }
                case 2:
                case 3: {
                    switch (joggSyncState.pageout(joggPage)) {
                        case -1:
                            return false;
                        case 0:
                            break;
                        case 1: {
                            joggStreamState.pagein(joggPage);

                            switch (joggStreamState.packetout(joggPacket)) {
                                case -1:
                                    return false;
                                case 0:
                                    break;
                                case 1: {
                                    jorbisInfo.synthesis_headerin(jorbisComment, joggPacket);
                                    packet++;

                                    if (packet == 4)
                                        needMoreData = false;

                                    break;
                                }
                            }

                            break;
                        }
                    }

                    break;
                }
            }

            index = joggSyncState.buffer(bufferSize);
            buffer = joggSyncState.data;

            if (count == 0 && needMoreData)
                return false;
        }

        return true;
    }

    private void readBody() {
        boolean needMoreData = true;

        while (needMoreData) {
            switch (joggSyncState.pageout(joggPage)) {
                case -1: {
                    // Proceed.
                }
                case 0:
                    break;
                case 1: {
                    joggStreamState.pagein(joggPage);

                    if (joggPage.granulepos() == 0) {
                        needMoreData = false;
                        break;
                    }

                    processPackets:
                    while (true) {
                        switch (joggStreamState.packetout(joggPacket)) {
                            case -1: {
                                // Proceed.
                            }
                            case 0:
                                break processPackets;
                            case 1:
                                decodeCurrentPacket();
                        }
                    }

                    if (joggPage.eos() != 0)
                        needMoreData = false;
                }
            }

            if (needMoreData) {
                index = joggSyncState.buffer(bufferSize);
                buffer = joggSyncState.data;

                try {
                    count = inputStream.read(buffer, index, bufferSize);
                } catch (IOException e) {
                    // This should only happen if the audio data is invalid
                    System.err.println("ERROR: unable to decode input file");
                    System.exit(4);
                }

                joggSyncState.wrote(count);

                if (count == 0)
                    needMoreData = false;
            }
        }
    }

    private void decodeCurrentPacket() {
        int samples, range;

        if (jorbisBlock.synthesis(joggPacket) == 0) {
            jorbisDspState.synthesis_blockin(jorbisBlock);
        }

        while ((samples = jorbisDspState.synthesis_pcmout(pcmInfo, pcmIndex)) > 0) {
            if (samples < convertedBufferSize) {
                range = samples;
            } else {
                range = convertedBufferSize;
            }

            for (int i = 0; i < jorbisInfo.channels; i++) {
                int sampleIndex = i * 2;
                for (int j = 0; j < range; j++) {
                    int value = (int) (pcmInfo[0][i][pcmIndex[i] + j] * 32767);

                    if (value > 32767)
                        value = 32767;
                    else if (value < -32768)
                        value = -32768;

                    if (value < 0)
                        value |= 32768;

                    convertedBuffer[sampleIndex] = (byte) (value);
                    convertedBuffer[sampleIndex + 1] = (byte) (value >>> 8);

                    sampleIndex += 2 * (jorbisInfo.channels);
                }
            }

            sourceDataLine.write(convertedBuffer, 0, 2 * jorbisInfo.channels * range);

            jorbisDspState.synthesis_read(range);
        }
    }

    private void cleanUp() {
        joggStreamState.clear();
        jorbisBlock.clear();
        jorbisDspState.clear();
        jorbisInfo.clear();
        joggSyncState.clear();

        try {
            inputStream.close();
        } catch (IOException e) {
        }
    }

    public void play() {
        playerThread.start();
    }

    @Override
    public void run() {
        if (inputStream == null)
            return;

        // Initialize codec
        initialize();

        // Read data header
        if (readHeader()) {

            // Initialize sound output
            if (initializeSound()) {

                // Play audio
                readBody();
            } else {
                System.err.println("ERROR: unable to initialize audio output");
                System.exit(2);
            }

        } else {
            System.err.println("ERROR: invalid input file specified");
            System.exit(3);
        }

        // Clean everything up
        cleanUp();
    }
}
