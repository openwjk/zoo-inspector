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
package com.zooinspector;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import com.zooinspector.core.ConfigStore;
import com.zooinspector.core.Inspector;
import com.zooinspector.core.Settings;
import com.zooinspector.ui.MainFrame;
import com.zooinspector.ui.Theme;

/**
 * 入口。用法：
 *
 * <pre>
 * java -jar zookeeper-dev-ZooInspector.jar
 * java -jar zookeeper-dev-ZooInspector.jar --connect localhost:2181/myapp --dark
 * </pre>
 */
public final class ZooInspectorApp
{
	private ZooInspectorApp()
	{
	}

	public static void main(final String[] args)
	{
		// ZooKeeper 客户端的日志只保留 warn 以上，避免刷屏
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
		System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss");
		System.setProperty("zookeeper.sasl.client", "false");

		String connect = null;
		String themeOverride = null;
		for (int i = 0; i < args.length; i++)
		{
			String a = args[i];
			if ("--connect".equals(a) && i + 1 < args.length)
			{
				connect = args[++i];
			}
			else if ("--dark".equals(a))
			{
				themeOverride = "dark";
			}
			else if ("--light".equals(a))
			{
				themeOverride = "light";
			}
			else if ("--help".equals(a) || "-h".equals(a))
			{
				System.out.println("Usage: java -jar zookeeper-dev-ZooInspector.jar "
						+ "[--connect host:2181[/chroot]] [--dark|--light]");
				return;
			}
		}

		final Settings settings = ConfigStore.loadSettings();
		if (themeOverride != null)
		{
			settings.theme = themeOverride;
		}
		final String autoConnect = connect;

		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					Theme.install(settings.theme, settings.uiFontSize);
					Inspector inspector = new Inspector();
					MainFrame frame = new MainFrame(inspector);
					frame.setVisible(true);
					if (autoConnect != null)
					{
						frame.connectHosts(autoConnect);
					}
				}
				catch (Throwable t)
				{
					t.printStackTrace();
					JOptionPane.showMessageDialog(null,
							"ZooInspector failed to start:\n" + t, "Startup error",
							JOptionPane.ERROR_MESSAGE);
					System.exit(1);
				}
			}
		});
	}
}