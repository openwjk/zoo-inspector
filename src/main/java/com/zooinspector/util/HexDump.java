/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zooinspector.util;

/** 十六进制查看器，用于二进制 znode 数据 */
public final class HexDump
{
	private static final char[] HEX = "0123456789abcdef".toCharArray();
	/** 最多渲染的行数，避免几十 MB 数据把界面拖死 */
	private static final int MAX_ROWS = 4096;

	private HexDump()
	{
	}

	public static String format(byte[] data)
	{
		if (data == null || data.length == 0)
		{
			return "(empty)";
		}
		int rows = (data.length + 15) / 16;
		boolean truncated = rows > MAX_ROWS;
		if (truncated)
		{
			rows = MAX_ROWS;
		}
		StringBuilder sb = new StringBuilder(rows * 78);
		char[] ascii = new char[16];
		for (int row = 0; row < rows; row++)
		{
			int base = row * 16;
			sb.append(String.format("%08x  ", base));
			for (int i = 0; i < 16; i++)
			{
				if (base + i < data.length)
				{
					int b = data[base + i] & 0xFF;
					sb.append(HEX[b >>> 4]).append(HEX[b & 0x0F]).append(' ');
					ascii[i] = (b >= 0x20 && b < 0x7F) ? (char) b : '.';
				}
				else
				{
					sb.append("   ");
					ascii[i] = ' ';
				}
				if (i == 7)
				{
					sb.append(' ');
				}
			}
			sb.append(' ').append(ascii).append('\n');
		}
		if (truncated)
		{
			sb.append("\n… truncated: showing first ").append(MAX_ROWS * 16).append(" of ")
					.append(data.length).append(" bytes\n");
		}
		return sb.toString();
	}
}