/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.zooinspector.dev;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/** 抓整屏，用于核对「jar 形态」运行时的真实渲染（含图标资源是否打进包） */
public final class ScreenShot
{
	public static void main(String[] args) throws Exception
	{
		File out = new File(args.length > 0 ? args[0] : "target/shots/screen.png");
		long delay = args.length > 1 ? Long.parseLong(args[1]) : 0;
		Thread.sleep(delay);
		out.getParentFile().mkdirs();
		Robot robot = new Robot();
		Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
		BufferedImage img = robot.createScreenCapture(screen);
		ImageIO.write(img, "png", out);
		System.out.println("SCREEN " + out.getAbsolutePath() + " " + img.getWidth() + "x"
				+ img.getHeight());
	}

	private ScreenShot()
	{
	}
}