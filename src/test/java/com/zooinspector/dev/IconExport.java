/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import com.zooinspector.ui.Icons;

/**
 * 把窗口图标 Icons.appImage 导出成 Windows 多尺寸 .ico，给桌面快捷方式用，
 * 保证快捷方式图标与程序运行时的窗口/任务栏图标完全一致。
 *
 * 用法：IconExport [输出.ico] [预览PNG目录]
 * 各尺寸一律写 32 位 BGRA 的 DIB（带 alpha），谁都能读。256 改用 PNG 条目能把文件缩到 1/3，
 * 但那是 Vista+ 才认的压缩格式，这里不图这点体积。
 */
public final class IconExport
{
	private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

	private IconExport()
	{
	}

	public static void main(String[] args) throws Exception
	{
		File out = new File(args.length > 0 ? args[0] : "dist/ZooInspector.ico");
		File previewDir = args.length > 1 ? new File(args[1]) : null;
		if (previewDir != null)
		{
			previewDir.mkdirs();
		}
		out.getParentFile().mkdirs();

		byte[][] images = new byte[SIZES.length][];
		for (int i = 0; i < SIZES.length; i++)
		{
			BufferedImage img = Icons.appImage(SIZES[i]);
			images[i] = dib(img);
			if (previewDir != null)
			{
				ImageIO.write(img, "png", new File(previewDir, "icon-" + SIZES[i] + ".png"));
			}
		}

		int total = 6 + 16 * SIZES.length;
		for (byte[] b : images)
		{
			total += b.length;
		}
		ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		buf.putShort((short) 0); // reserved
		buf.putShort((short) 1); // type = icon
		buf.putShort((short) SIZES.length);
		int offset = 6 + 16 * SIZES.length;
		for (int i = 0; i < SIZES.length; i++)
		{
			int s = SIZES[i];
			buf.put((byte) (s >= 256 ? 0 : s)); // 0 表示 256
			buf.put((byte) (s >= 256 ? 0 : s));
			buf.put((byte) 0); // 调色板颜色数，真彩色为 0
			buf.put((byte) 0); // reserved
			buf.putShort((short) 1); // planes
			buf.putShort((short) 32); // bpp
			buf.putInt(images[i].length);
			buf.putInt(offset);
			offset += images[i].length;
		}
		for (byte[] b : images)
		{
			buf.put(b);
		}
		Files.write(out.toPath(), buf.array());
		System.out.println("Wrote " + out.getPath() + " (" + total + " bytes, "
				+ SIZES.length + " sizes)");
	}

	/** BITMAPINFOHEADER + 自底向上的 BGRA 像素 + 全 0 的 AND 掩码 */
	private static byte[] dib(BufferedImage img)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int maskRow = (w + 31) / 32 * 4;
		int pixels = w * h * 4;
		ByteBuffer buf = ByteBuffer.allocate(40 + pixels + maskRow * h)
				.order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(40); // biSize
		buf.putInt(w);
		buf.putInt(h * 2); // XOR 位图 + AND 掩码，高度翻倍
		buf.putShort((short) 1); // biPlanes
		buf.putShort((short) 32); // biBitCount
		buf.putInt(0); // BI_RGB
		buf.putInt(pixels + maskRow * h);
		buf.putInt(0); // biXPelsPerMeter
		buf.putInt(0); // biYPelsPerMeter
		buf.putInt(0); // biClrUsed
		buf.putInt(0); // biClrImportant
		for (int y = h - 1; y >= 0; y--)
		{
			for (int x = 0; x < w; x++)
			{
				int argb = img.getRGB(x, y);
				buf.put((byte) (argb & 0xFF));
				buf.put((byte) ((argb >> 8) & 0xFF));
				buf.put((byte) ((argb >> 16) & 0xFF));
				buf.put((byte) ((argb >> 24) & 0xFF));
			}
		}
		// AND 掩码留全 0：透明度由 alpha 通道决定
		return buf.array();
	}
}