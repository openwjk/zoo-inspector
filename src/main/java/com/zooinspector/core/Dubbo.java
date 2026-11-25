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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;

/**
 * Dubbo 风格布局的识别与展示。
 *
 * <p>「接口节点」= 直接子节点里含 providers 或 consumers 的那一层，
 * 例如 {@code /dubbo/com.foo.OrderService}。按子节点名识别，不写死根路径，
 * 所以 /dubbo、/services、/sofa 等任何根都适用。
 *
 * <p>provider/consumer 的节点名是 URL 编码的长串，直接显示既噪音大又搜不清；
 * 这里解码后抽出 host:port 与 application/version 做展示。
 */
public final class Dubbo
{
	public static final int PROVIDERS = 1;
	public static final int CONSUMERS = 2;
	public static final int CONFIGURATORS = 4;
	public static final int ROUTERS = 8;

	public static final String PROVIDERS_NAME = "providers";
	public static final String CONSUMERS_NAME = "consumers";
	public static final String CONFIGURATORS_NAME = "configurators";
	public static final String ROUTERS_NAME = "routers";

	private Dubbo()
	{
	}

	/** 爬取时顺手算出子节点里有哪些分类，零额外请求 */
	public static byte flagsOf(List<String> childNames)
	{
		int flags = 0;
		for (String name : childNames)
		{
			if (PROVIDERS_NAME.equals(name))
			{
				flags |= PROVIDERS;
			}
			else if (CONSUMERS_NAME.equals(name))
			{
				flags |= CONSUMERS;
			}
			else if (CONFIGURATORS_NAME.equals(name))
			{
				flags |= CONFIGURATORS;
			}
			else if (ROUTERS_NAME.equals(name))
			{
				flags |= ROUTERS;
			}
		}
		return (byte) flags;
	}

	public static boolean isInterface(byte flags)
	{
		return (flags & (PROVIDERS | CONSUMERS)) != 0;
	}

	public static boolean isCategoryName(String name)
	{
		return PROVIDERS_NAME.equals(name) || CONSUMERS_NAME.equals(name)
				|| CONFIGURATORS_NAME.equals(name) || ROUTERS_NAME.equals(name);
	}

	/** 该接口下实际存在的分类，按 providers / consumers / configurators / routers 排序 */
	public static String[] categoriesOf(byte flags)
	{
		String[] all = new String[4];
		int n = 0;
		if ((flags & PROVIDERS) != 0)
		{
			all[n++] = PROVIDERS_NAME;
		}
		if ((flags & CONSUMERS) != 0)
		{
			all[n++] = CONSUMERS_NAME;
		}
		if ((flags & CONFIGURATORS) != 0)
		{
			all[n++] = CONFIGURATORS_NAME;
		}
		if ((flags & ROUTERS) != 0)
		{
			all[n++] = ROUTERS_NAME;
		}
		return java.util.Arrays.copyOf(all, n);
	}

	public static String decode(String nodeName)
	{
		if (nodeName == null || nodeName.indexOf('%') < 0 && nodeName.indexOf('+') < 0)
		{
			return nodeName;
		}
		try
		{
			return URLDecoder.decode(nodeName, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			return nodeName;
		}
		catch (IllegalArgumentException e)
		{
			// 非法转义序列，按原样显示
			return nodeName;
		}
	}

	/** 看起来是不是编码后的 URL 节点（provider/consumer 实例） */
	public static boolean isInstanceName(String nodeName)
	{
		if (nodeName == null)
		{
			return false;
		}
		String decoded = decode(nodeName);
		int at = decoded.indexOf("://");
		return at > 0 && at < 24;
	}

	/** 实例主标题：host:port（取不到就退回解码后的短串） */
	public static String instanceLabel(String nodeName)
	{
		String decoded = decode(nodeName);
		int scheme = decoded.indexOf("://");
		if (scheme < 0)
		{
			return decoded;
		}
		int from = scheme + 3;
		int to = decoded.indexOf('/', from);
		String authority = to < 0 ? decoded.substring(from) : decoded.substring(from, to);
		int q = authority.indexOf('?');
		if (q >= 0)
		{
			authority = authority.substring(0, q);
		}
		return authority.isEmpty() ? decoded : authority;
	}

	/** 实例副标题：application / version / group 等关键参数 */
	public static String instanceDetail(String nodeName)
	{
		String decoded = decode(nodeName);
		int q = decoded.indexOf('?');
		if (q < 0)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (String key : new String[] { "application", "version", "group" })
		{
			String v = param(decoded, key);
			if (v != null && !v.isEmpty())
			{
				if (sb.length() > 0)
				{
					sb.append(" · ");
				}
				sb.append(key).append('=').append(v);
			}
		}
		return sb.toString();
	}

	public static String param(String decodedUrl, String key)
	{
		int q = decodedUrl.indexOf('?');
		if (q < 0)
		{
			return null;
		}
		String query = decodedUrl.substring(q + 1);
		for (String pair : query.split("&"))
		{
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(key))
			{
				return pair.substring(eq + 1);
			}
		}
		return null;
	}
}