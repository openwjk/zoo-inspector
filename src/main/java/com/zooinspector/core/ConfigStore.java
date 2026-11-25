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
package com.zooinspector.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * 配置持久化。默认落在**启动的 jar 同级的 config/ 目录**，
 * 这样整个目录拷到别的机器上配置跟着走（绿色便携）。
 *
 * <p>位置优先级：
 * <ol>
 * <li>{@code -Dzooinspector.config.dir=<path>} 显式指定；
 * <li>jar（或 classes 目录）同级的 {@code config/}；
 * <li>该位置不可写时（例如装在 Program Files 下）回退 {@code ~/.zooinspector}。
 * </ol>
 *
 * <p>首次启用新位置时，会把 {@code ~/.zooinspector} 里已有的配置拷过来，
 * 只补缺失的文件，不覆盖。
 */
public final class ConfigStore
{
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile File resolvedDir;

	private ConfigStore()
	{
	}

	public static File dir()
	{
		File d = resolvedDir;
		if (d == null)
		{
			synchronized (ConfigStore.class)
			{
				d = resolvedDir;
				if (d == null)
				{
					d = resolveDir();
					resolvedDir = d;
				}
			}
		}
		if (!d.isDirectory())
		{
			d.mkdirs();
		}
		return d;
	}

	private static File resolveDir()
	{
		File legacy = new File(System.getProperty("user.home"), ".zooinspector");
		String override = System.getProperty("zooinspector.config.dir");
		File target;
		if (override != null && !override.trim().isEmpty())
		{
			target = new File(override.trim());
		}
		else
		{
			File appDir = appDir();
			File portable = appDir == null ? null : new File(appDir, "config");
			target = portable != null && writable(portable) ? portable : legacy;
		}
		if (!target.isDirectory())
		{
			target.mkdirs();
		}
		migrate(legacy, target);
		return target;
	}

	/** 启动物所在目录：jar 取其父目录，classes 目录取自身 */
	private static File appDir()
	{
		try
		{
			java.security.CodeSource cs = ConfigStore.class.getProtectionDomain().getCodeSource();
			if (cs == null || cs.getLocation() == null)
			{
				return null;
			}
			// 走 URI 而不是 getPath()，路径里的空格/中文才不会被 URL 编码破坏
			File f = new File(cs.getLocation().toURI());
			return f.isFile() ? f.getParentFile() : f;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static boolean writable(File d)
	{
		if (!d.isDirectory() && !d.mkdirs())
		{
			return false;
		}
		File probe = new File(d, ".write-probe");
		try
		{
			if (!probe.createNewFile())
			{
				return false;
			}
			probe.delete();
			return true;
		}
		catch (IOException e)
		{
			return false;
		}
		finally
		{
			if (probe.isFile())
			{
				probe.delete();
			}
		}
	}

	/** 只补目标目录里缺的文件，绝不覆盖已有配置 */
	private static void migrate(File from, File to)
	{
		if (from.equals(to) || !from.isDirectory())
		{
			return;
		}
		for (String name : new String[] { "profiles.json", "settings.json" })
		{
			File src = new File(from, name);
			File dst = new File(to, name);
			if (src.isFile() && !dst.isFile())
			{
				try
				{
					java.nio.file.Files.copy(src.toPath(), dst.toPath());
				}
				catch (IOException e)
				{
					System.err.println("[ZooInspector] could not migrate " + src + ": "
							+ e.getMessage());
				}
			}
		}
	}

	public static Settings loadSettings()
	{
		Settings s = read(new File(dir(), "settings.json"), Settings.class);
		return s == null ? new Settings() : s;
	}

	public static void saveSettings(Settings s)
	{
		write(new File(dir(), "settings.json"), s);
	}

	public static List<ConnectProfile> loadProfiles()
	{
		Type type = new TypeToken<List<ConnectProfile>>()
		{
		}.getType();
		List<ConnectProfile> list = read(new File(dir(), "profiles.json"), type);
		if (list == null)
		{
			list = new ArrayList<ConnectProfile>();
		}
		if (list.isEmpty())
		{
			ConnectProfile local = new ConnectProfile();
			local.name = "localhost";
			local.hosts = "localhost:2181";
			list.add(local);
		}
		return list;
	}

	public static void saveProfiles(List<ConnectProfile> profiles)
	{
		List<ConnectProfile> stored = new ArrayList<ConnectProfile>();
		for (ConnectProfile p : profiles)
		{
			stored.add(p.forStorage());
		}
		write(new File(dir(), "profiles.json"), stored);
	}

	private static <T> T read(File f, Type type)
	{
		if (!f.isFile())
		{
			return null;
		}
		Reader r = null;
		try
		{
			r = new InputStreamReader(Files.newInputStream(f.toPath()), UTF8);
			return GSON.fromJson(r, type);
		}
		catch (Exception e)
		{
			// 配置损坏时回退到默认值，不要让应用起不来
			System.err.println("[ZooInspector] ignoring unreadable config " + f + ": "
					+ e.getMessage());
			return null;
		}
		finally
		{
			close(r);
		}
	}

	private static void write(File f, Object value)
	{
		Writer w = null;
		try
		{
			w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), UTF8);
			GSON.toJson(value, w);
		}
		catch (IOException e)
		{
			System.err.println("[ZooInspector] could not save " + f + ": " + e.getMessage());
		}
		finally
		{
			close(w);
		}
	}

	private static void close(java.io.Closeable c)
	{
		if (c != null)
		{
			try
			{
				c.close();
			}
			catch (IOException e)
			{
				// ignore
			}
		}
	}
}