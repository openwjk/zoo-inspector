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

import java.util.ArrayList;
import java.util.List;

/** 连接配置，可持久化为 profile（Gson 直接序列化字段） */
public class ConnectProfile
{
	public String name = "";
	/** 支持 host1:2181,host2:2181/chroot */
	public String hosts = "localhost:2181";
	public int sessionTimeoutMs = 30000;
	public int connectTimeoutMs = 15000;
	public List<Auth> auths = new ArrayList<Auth>();
	/** 默认不把密码写进磁盘，只在本次运行的内存里保留 */
	public boolean rememberSecrets = false;

	public static class Auth
	{
		public String scheme = "digest";
		public String id = "";
		public String secret = "";

		public Auth()
		{
		}

		public Auth(String scheme, String id, String secret)
		{
			this.scheme = scheme;
			this.id = id;
			this.secret = secret;
		}

		public boolean isEmpty()
		{
			return id == null || id.trim().isEmpty();
		}
	}

	public ConnectProfile copy()
	{
		ConnectProfile p = new ConnectProfile();
		p.name = name;
		p.hosts = hosts;
		p.sessionTimeoutMs = sessionTimeoutMs;
		p.connectTimeoutMs = connectTimeoutMs;
		p.rememberSecrets = rememberSecrets;
		for (Auth a : auths)
		{
			p.auths.add(new Auth(a.scheme, a.id, a.secret));
		}
		return p;
	}

	/** 落盘用的副本：未勾选记住密码时抹掉 secret */
	public ConnectProfile forStorage()
	{
		ConnectProfile p = copy();
		if (!rememberSecrets)
		{
			for (Auth a : p.auths)
			{
				a.secret = "";
			}
		}
		return p;
	}

	public String displayName()
	{
		if (name != null && !name.trim().isEmpty())
		{
			return name.trim();
		}
		return hosts;
	}

	@Override
	public String toString()
	{
		return displayName();
	}
}