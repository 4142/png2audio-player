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

package com.four142.png2audio.player;

import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

public class Player {

    public static void main(String[] args) {

        if (args.length != 1) {
            System.err.println("USAGE: java -jar png2audio-player.jar <input file>");
            System.exit(1);
        }

        File input = new File(args[0]);

        byte[] data = null;

        // Try and decode the input file
        try {
            data = input(input);
        } catch (IOException e) {
            System.err.println("ERROR: unable to open input file, please make sure that it exists and is readable");
            System.exit(1);
        }

        OggPlayer oggPlayer = new OggPlayer(new ByteArrayInputStream(data));

        // Play the decoded data
        oggPlayer.play();
    }

    /**
     * Converts the specified png image into a byte array containing the original data.
     *
     * @param input the png file to convert
     * @return a byte array containing the original data
     * @throws IOException if the input file could not be opened
     */
    public static byte[] input(File input) throws IOException {

        // PngReader won't throw exceptions if the file can't be read
        if (!input.canRead()) {
            throw new IOException("unable to read input file");
        }

        PngReader pngReader = new PngReader(input);

        // Buffer for decoded data
        byte[] data = null;

        // Current data position
        int position = -1;

        ImageLineInt imageLine;
        int[] scanLine;

        do {
            imageLine = (ImageLineInt) pngReader.readRow();
            scanLine = imageLine.getScanline();

            for (int x = 0; x < scanLine.length / 4; x++) {
                if (position > -1) {
                    for (int i = 0; i < 4; i++) {
                        if (position < data.length)
                            data[position++] = (byte) scanLine[x * 4 + i];
                    }
                } else {
                    // Read the length from the first pixel
                    int length = 0;

                    for (int i = 0; i < 4; i++) {
                        length = (length << 8) + scanLine[x * 4 + i];
                    }

                    data = new byte[length];

                    position++;
                }

                // Break as soon as we've read all data
                if (position == data.length) break;
            }

            if (position == data.length) break;
        } while (pngReader.hasMoreRows());

        // Finish reading
        pngReader.end();

        return data;
    }

}
