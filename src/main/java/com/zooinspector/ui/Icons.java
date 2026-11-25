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
package com.zooinspector.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.swing.Icon;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * 图标加载。SVG 里统一用中性灰 #6B7280 描边，运行时按主题替换成前景色或柔和色；
 * 其它颜色（ephemeral 的琥珀、成功的绿）原样保留，深浅色下都成立。
 */
public final class Icons
{
	private static final Color BASE = new Color(0x6B7280);
	private static final Map<String, FlatSVGIcon> CACHE = new HashMap<String, FlatSVGIcon>();

	private Icons()
	{
	}

	/** 标准动作图标，跟随主题前景色 */
	public static Icon get(String name)
	{
		return get(name, 16, false);
	}

	public static Icon get(String name, int size)
	{
		return get(name, size, false);
	}

	/** 柔和版本，用于树里的文件夹/叶子等次要图形 */
	public static Icon soft(String name)
	{
		return get(name, 16, true);
	}

	public static synchronized Icon get(String name, int size, final boolean soft)
	{
		String key = name + "@" + size + (soft ? ":soft" : "");
		FlatSVGIcon icon = CACHE.get(key);
		if (icon == null)
		{
			icon = new FlatSVGIcon("icons/" + name + ".svg", size, size);
			icon.setColorFilter(new FlatSVGIcon.ColorFilter(new Function<Color, Color>()
			{
				@Override
				public Color apply(Color c)
				{
					if (c != null && c.getRGB() == BASE.getRGB())
					{
						return soft ? Theme.mix(Theme.foreground(), Theme.background(), 0.62f)
								: Theme.foreground();
					}
					return c;
				}
			}));
			CACHE.put(key, icon);
		}
		return icon;
	}

	public static synchronized void clearCache()
	{
		CACHE.clear();
		FlatSVGIcon.clearSVGDocumentCache();
	}

	/** 程序化绘制的窗口图标：强调色圆角底 + 白色节点树，替掉默认 Java 咖啡杯 */
	public static BufferedImage appImage(int size)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		float s = size / 32f;
		g.setColor(new Color(0x0E7490));
		g.fill(new RoundRectangle2D.Float(1 * s, 1 * s, 30 * s, 30 * s, 9 * s, 9 * s));
		g.setColor(new Color(0x22A5BF));
		g.fill(new RoundRectangle2D.Float(1 * s, 1 * s, 30 * s, 16 * s, 9 * s, 9 * s));
		g.setColor(Color.WHITE);
		g.setStroke(new java.awt.BasicStroke(1.9f * s, java.awt.BasicStroke.CAP_ROUND,
				java.awt.BasicStroke.JOIN_ROUND));
		// 一父两子的节点树
		g.draw(new java.awt.geom.Line2D.Float(16 * s, 10 * s, 9 * s, 21 * s));
		g.draw(new java.awt.geom.Line2D.Float(16 * s, 10 * s, 23 * s, 21 * s));
		float r = 3.1f * s;
		g.fill(new java.awt.geom.Ellipse2D.Float(16 * s - r, 10 * s - r, r * 2, r * 2));
		float r2 = 2.6f * s;
		g.fill(new java.awt.geom.Ellipse2D.Float(9 * s - r2, 21 * s - r2, r2 * 2, r2 * 2));
		g.fill(new java.awt.geom.Ellipse2D.Float(23 * s - r2, 21 * s - r2, r2 * 2, r2 * 2));
		g.dispose();
		return img;
	}
}