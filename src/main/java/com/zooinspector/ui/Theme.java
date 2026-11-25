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
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/** FlatLaf 主题安装与全局观感调参 */
public final class Theme
{
	/** 强调色：偏青的蓝绿，和 ZooKeeper 的「集群/节点」气质相符，也不撞默认蓝 */
	private static final String ACCENT_LIGHT = "#0E7490";
	private static final String ACCENT_DARK = "#12798C";

	private static boolean dark;
	private static String uiFamily = "Dialog";
	private static String monoFamily = "Monospaced";

	private Theme()
	{
	}

	public static void install(String themeName, int fontSize)
	{
		dark = "dark".equalsIgnoreCase(themeName);
		// 统一标题栏，去掉系统原生边框的割裂感
		System.setProperty("flatlaf.useWindowDecorations", "true");
		System.setProperty("flatlaf.menuBarEmbedded", "true");
		System.setProperty("flatlaf.useNativeLibrary", "false");
		JFrame.setDefaultLookAndFeelDecorated(true);
		JDialog.setDefaultLookAndFeelDecorated(true);

		FlatLaf.setGlobalExtraDefaults(Collections.singletonMap("@accentColor",
				dark ? ACCENT_DARK : ACCENT_LIGHT));
		FlatLaf.setup(dark ? new FlatDarkLaf() : new FlatLightLaf());
		applyDefaults(fontSize);
		Icons.clearCache();
		FlatLaf.updateUI();
	}

	public static boolean isDark()
	{
		return dark;
	}

	private static void applyDefaults(int fontSize)
	{
		uiFamily = pick(new String[] { "Segoe UI", "Inter", "Noto Sans", "Dialog" });
		monoFamily = pick(new String[] { "JetBrains Mono", "Cascadia Mono", "Consolas",
				"DejaVu Sans Mono", "Monospaced" });

		FontUIResource ui = new FontUIResource(uiFamily, Font.PLAIN, fontSize);
		for (String key : new String[] { "Button.font", "ToggleButton.font", "Label.font",
				"TextField.font", "FormattedTextField.font", "PasswordField.font", "TextArea.font",
				"TextPane.font", "EditorPane.font", "ComboBox.font", "List.font", "Tree.font",
				"Table.font", "TableHeader.font", "TabbedPane.font", "CheckBox.font",
				"RadioButton.font", "Menu.font", "MenuItem.font", "CheckBoxMenuItem.font",
				"RadioButtonMenuItem.font", "MenuBar.font", "PopupMenu.font", "TitledBorder.font",
				"ToolTip.font", "ProgressBar.font", "Spinner.font", "Slider.font", "OptionPane.font",
				"Panel.font", "ScrollPane.font", "Viewport.font", "RootPane.font", "TitlePane.font" })
		{
			UIManager.put(key, ui);
		}

		// 圆角与留白：控件统一 10px 圆角，滚动条收窄，焦点环收细
		UIManager.put("Component.arc", 10);
		UIManager.put("Button.arc", 10);
		UIManager.put("TextComponent.arc", 10);
		UIManager.put("CheckBox.arc", 6);
		UIManager.put("ProgressBar.arc", 8);
		UIManager.put("Component.focusWidth", 1);
		UIManager.put("Component.innerFocusWidth", 0);
		UIManager.put("Button.innerFocusWidth", 0);
		UIManager.put("ScrollBar.width", 12);
		UIManager.put("ScrollBar.thumbArc", 8);
		UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
		UIManager.put("ScrollBar.showButtons", false);
		UIManager.put("List.selectionArc", 8);
		UIManager.put("Tree.selectionArc", 8);
		UIManager.put("Tree.rowHeight", 24);
		UIManager.put("Tree.paintLines", false);
		UIManager.put("Tree.repaintWholeRow", true);
		UIManager.put("Table.rowHeight", 24);
		UIManager.put("Table.showHorizontalLines", false);
		UIManager.put("Table.showVerticalLines", false);
		UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 1));
		UIManager.put("TabbedPane.tabHeight", 34);
		UIManager.put("TabbedPane.contentSeparatorHeight", 1);
		UIManager.put("TabbedPane.showTabSeparators", false);
		UIManager.put("TitlePane.unifiedBackground", true);
		UIManager.put("SplitPane.dividerSize", 7);
		UIManager.put("SplitPaneDivider.style", "plain");
		UIManager.put("OptionPane.messageAreaBorder", javax.swing.BorderFactory.createEmptyBorder());
	}

	private static String pick(String[] candidates)
	{
		Set<String> available = new HashSet<String>(Arrays.asList(GraphicsEnvironment
				.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		for (String c : candidates)
		{
			if (available.contains(c))
			{
				return c;
			}
		}
		return candidates[candidates.length - 1];
	}

	public static Font uiFont(int size)
	{
		return new Font(uiFamily, Font.PLAIN, size);
	}

	public static Font uiFont(int style, int size)
	{
		return new Font(uiFamily, style, size);
	}

	public static Font monoFont(int size)
	{
		return new Font(monoFamily, Font.PLAIN, size);
	}

	public static String monoFamily()
	{
		return monoFamily;
	}

	/** 次要文字色（父路径、badge、提示） */
	public static Color muted()
	{
		Color c = UIManager.getColor("Label.disabledForeground");
		return c == null ? new Color(0x808080) : c;
	}

	public static Color foreground()
	{
		Color c = UIManager.getColor("Label.foreground");
		return c == null ? Color.DARK_GRAY : c;
	}

	public static Color accent()
	{
		Color c = UIManager.getColor("Component.accentColor");
		if (c == null)
		{
			c = UIManager.getColor("TabbedPane.underlineColor");
		}
		return c == null ? new Color(0x0E7490) : c;
	}

	public static Color background()
	{
		Color c = UIManager.getColor("Panel.background");
		return c == null ? Color.WHITE : c;
	}

	/** 按比例混合两色，用于生成柔和的图标色与分隔背景 */
	public static Color mix(Color a, Color b, float ratio)
	{
		float r = Math.max(0f, Math.min(1f, ratio));
		return new Color(Math.round(a.getRed() * r + b.getRed() * (1 - r)),
				Math.round(a.getGreen() * r + b.getGreen() * (1 - r)),
				Math.round(a.getBlue() * r + b.getBlue() * (1 - r)));
	}

	/**
	 * 按背景亮度选可读的文字色。
	 *
	 * <p>不能直接用 FlatLaf 的 {@code Tree.selectionForeground}：它会按「选中背景」
	 * 的亮度推导，而失焦时实际用的是另一套更深的 inactive 背景，
	 * 于是出现深底深字。自绘 renderer 一律用这里的判断。
	 */
	public static Color readableOn(Color bg)
	{
		if (bg == null)
		{
			return foreground();
		}
		double luminance = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
		return luminance > 0.55 ? new Color(0x23282B) : new Color(0xEEF2F4);
	}

	/** 选中行背景：失焦时用 inactive 变体，与 L&F 实际绘制保持一致 */
	public static Color selectionBackground(String uiPrefix, Color fallback, boolean focused)
	{
		String key = focused ? uiPrefix + ".selectionBackground"
				: uiPrefix + ".selectionInactiveBackground";
		Color c = UIManager.getColor(key);
		if (c == null)
		{
			c = UIManager.getColor(uiPrefix + ".selectionBackground");
		}
		return c == null ? fallback : c;
	}

	public static Color success()
	{
		return dark ? new Color(0x4ADE80) : new Color(0x16A34A);
	}

	public static Color danger()
	{
		return dark ? new Color(0xF87171) : new Color(0xDC2626);
	}

	public static Color warn()
	{
		return dark ? new Color(0xFBBF24) : new Color(0xD97706);
	}
}