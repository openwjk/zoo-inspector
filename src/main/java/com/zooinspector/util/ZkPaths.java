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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** znode 路径处理，统一 "/" 根节点与拼接规则 */
public final class ZkPaths
{
	public static final String ROOT = "/";

	private ZkPaths()
	{
	}

	/** 规范化：保证以 / 开头、去掉结尾多余的 /（根节点保留 "/"） */
	public static String normalize(String path)
	{
		if (path == null || path.isEmpty())
		{
			return ROOT;
		}
		String p = path.trim();
		if (!p.startsWith(ROOT))
		{
			p = ROOT + p;
		}
		while (p.length() > 1 && p.endsWith(ROOT))
		{
			p = p.substring(0, p.length() - 1);
		}
		return p.isEmpty() ? ROOT : p;
	}

	public static String join(String parent, String name)
	{
		String p = normalize(parent);
		String n = name.startsWith(ROOT) ? name.substring(1) : name;
		return ROOT.equals(p) ? ROOT + n : p + ROOT + n;
	}

	public static String parent(String path)
	{
		String p = normalize(path);
		if (ROOT.equals(p))
		{
			return null;
		}
		int i = p.lastIndexOf('/');
		return i <= 0 ? ROOT : p.substring(0, i);
	}

	public static String name(String path)
	{
		String p = normalize(path);
		if (ROOT.equals(p))
		{
			return ROOT;
		}
		return p.substring(p.lastIndexOf('/') + 1);
	}

	/** 从根到自身的完整祖先链，含自身，用于「在树中定位」 */
	public static List<String> ancestors(String path)
	{
		String p = normalize(path);
		List<String> chain = new ArrayList<String>();
		chain.add(ROOT);
		if (ROOT.equals(p))
		{
			return chain;
		}
		StringBuilder sb = new StringBuilder();
		for (String segment : p.substring(1).split("/"))
		{
			sb.append('/').append(segment);
			chain.add(sb.toString());
		}
		return chain;
	}

	/** 相对根的深度，根为 0 */
	public static int depth(String path)
	{
		String p = normalize(path);
		if (ROOT.equals(p))
		{
			return 0;
		}
		int d = 0;
		for (int i = 0; i < p.length(); i++)
		{
			if (p.charAt(i) == '/')
			{
				d++;
			}
		}
		return d;
	}

	/** znode 名字合法性：非空、不含 /、不是 . 或 ..、不含 \u0000 */
	public static String validateName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return "Name must not be empty.";
		}
		if (name.contains("/"))
		{
			return "Name must not contain '/'. Create one level at a time.";
		}
		if (".".equals(name) || "..".equals(name))
		{
			return "'.' and '..' are not valid znode names.";
		}
		if (name.indexOf('\u0000') >= 0)
		{
			return "Name must not contain null characters.";
		}
		return null;
	}

	/** 名字自然序：忽略大小写，但尾部数字按数值比较（sequential 节点更直观） */
	public static final java.util.Comparator<String> NATURAL = new java.util.Comparator<String>()
	{
		@Override
		public int compare(String a, String b)
		{
			int i = 0;
			int j = 0;
			while (i < a.length() && j < b.length())
			{
				char ca = a.charAt(i);
				char cb = b.charAt(j);
				if (Character.isDigit(ca) && Character.isDigit(cb))
				{
					int si = i;
					int sj = j;
					while (i < a.length() && Character.isDigit(a.charAt(i)))
					{
						i++;
					}
					while (j < b.length() && Character.isDigit(b.charAt(j)))
					{
						j++;
					}
					String na = a.substring(si, i);
					String nb = b.substring(sj, j);
					int cmp = Integer.compare(na.length(), nb.length());
					if (cmp == 0)
					{
						cmp = na.compareTo(nb);
					}
					if (cmp != 0)
					{
						return cmp;
					}
				}
				else
				{
					if (ca != cb)
					{
						return Character.compare(Character.toLowerCase(ca),
								Character.toLowerCase(cb));
					}
					i++;
					j++;
				}
			}
			return Integer.compare(a.length() - i, b.length() - j);
		}
	};

	/** 按名字自然序排序 */
	public static List<String> sorted(List<String> names)
	{
		List<String> copy = new ArrayList<String>(names);
		Collections.sort(copy, NATURAL);
		return copy;
	}
}