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

/** 配置持久化：~/.zooinspector/{settings,profiles}.json */
public final class ConfigStore
{
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ConfigStore()
	{
	}

	public static File dir()
	{
		File f = new File(System.getProperty("user.home"), ".zooinspector");
		if (!f.isDirectory())
		{
			f.mkdirs();
		}
		return f;
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