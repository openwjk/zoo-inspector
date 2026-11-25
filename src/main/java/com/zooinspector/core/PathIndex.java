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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.zooinspector.util.ZkPaths;

/**
 * 全量路径索引 + 查询。构建完成后不可变，查询线程可以无锁读。
 *
 * <p>存储上做了两处省内存的处理：
 * <ul>
 * <li>不单独存节点名，只存名字在路径中的起始偏移 {@code nameStart}，
 * 名字区间的 substring 搜索直接用 {@code indexOf(needle, nameStart)} 完成，零分配；
 * <li>{@code String.toLowerCase} 在无需转换时返回自身，纯小写路径（绝大多数 znode）
 * 的 {@code lower} 与 {@code paths} 共享同一个 String，不额外占内存。
 */
public final class PathIndex
{
	public enum Mode
	{
		CONTAINS("Contains"), NAME("Name only"), GLOB("Glob"), REGEX("Regex"), EXACT(
				"Exact path"), DATA("Data");

		public final String label;

		Mode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public static final class Hit
	{
		public final String path;
		public final String name;
		public final int numChildren;
		public final int dataLength;
		public final boolean ephemeral;
		public final int tier;
		/** 索引内下标，便于 O(1) 取回内容片段 */
		final int index;
		public String snippet;
		/** 聚合视图：这一行是接口节点 */
		public boolean interfaceNode;
		/** 接口下的 provider / consumer 数量，-1 表示没有该分类 */
		public int providers = -1;
		public int consumers = -1;
		/** 聚合时真正命中的后代节点（用于解释「为什么这个接口被搜到」） */
		public String matchedPath;
		/** 内容片段取哪一行 */
		int snippetRow;

		Hit(int index, String path, int nameStart, int numChildren, int dataLength,
				boolean ephemeral, int tier)
		{
			this.index = index;
			this.snippetRow = index;
			this.path = path;
			this.name = ZkPaths.ROOT.equals(path) ? "/" : path.substring(nameStart);
			this.numChildren = numChildren;
			this.dataLength = dataLength;
			this.ephemeral = ephemeral;
			this.tier = tier;
		}

		public String parent()
		{
			String p = ZkPaths.parent(path);
			return p == null ? "" : p;
		}
	}

	public static final class Result
	{
		public final List<Hit> hits;
		public final int total;
		public final boolean partial;
		public final long elapsedMs;
		public final String error;
		/** 聚合前的原始命中节点数，用于「40 个接口 · 7400 个节点命中」 */
		public int rawTotal;

		Result(List<Hit> hits, int total, boolean partial, long elapsedMs, String error)
		{
			this.hits = hits;
			this.total = total;
			this.rawTotal = total;
			this.partial = partial;
			this.elapsedMs = elapsedMs;
			this.error = error;
		}

		static Result error(String message)
		{
			return new Result(Collections.<Hit> emptyList(), 0, false, 0, message);
		}
	}

	public interface Publisher
	{
		void publish(Result result);
	}

	public static final PathIndex EMPTY = new PathIndex(new String[0], new String[0], new int[0],
			new int[0], new int[0], new boolean[0], new byte[0], null, 0, false, 0, 0L, 0);

	/** 每档最多收集这么多候选再排序，避免超大结果集排序开销 */
	private static final int COLLECT_CAP = 2000;
	private static final int CHUNK = 16384;

	final String[] paths;
	final String[] lower;
	final int[] nameStart;
	final int[] numChildren;
	final int[] dataLength;
	final boolean[] ephemeral;
	/** 每行子节点里有哪些 Dubbo 分类（providers/consumers/...）的位掩码 */
	final byte[] categories;
	final String[] lowerData;
	public final int count;
	public final boolean dataIndexed;
	public final int errors;
	public final long builtAt;
	/** 数据索引因内存上限被截断时为 true */
	public final int dataSkipped;

	/** 派生缓存：路径 -> 行号。按需建一次，之后不可变可无锁读 */
	private volatile java.util.Map<String, Integer> rowIndex;
	/** 派生缓存：每行归属的接口行，-1 表示不在任何接口下 */
	private volatile int[] interfaceOwner;

	PathIndex(String[] paths, String[] lower, int[] nameStart, int[] numChildren, int[] dataLength,
			boolean[] ephemeral, byte[] categories, String[] lowerData, int count,
			boolean dataIndexed, int errors, long builtAt, int dataSkipped)
	{
		this.paths = paths;
		this.lower = lower;
		this.nameStart = nameStart;
		this.numChildren = numChildren;
		this.dataLength = dataLength;
		this.ephemeral = ephemeral;
		this.categories = categories;
		this.lowerData = lowerData;
		this.count = count;
		this.dataIndexed = dataIndexed;
		this.errors = errors;
		this.builtAt = builtAt;
		this.dataSkipped = dataSkipped;
	}

	// ---------------- 派生查询：接口归属与子节点 ----------------

	public boolean isInterfaceRow(int row)
	{
		return row >= 0 && row < count && Dubbo.isInterface(categories[row]);
	}

	public boolean isInterfacePath(String path)
	{
		return isInterfaceRow(rowOf(path));
	}

	public byte categoriesOf(String path)
	{
		int row = rowOf(path);
		return row < 0 ? 0 : categories[row];
	}

	/** 路径 -> 行号，找不到返回 -1 */
	public int rowOf(String path)
	{
		java.util.Map<String, Integer> map = rowIndex;
		if (map == null)
		{
			synchronized (this)
			{
				map = rowIndex;
				if (map == null)
				{
					map = new java.util.HashMap<String, Integer>(Math.max(16, count * 2));
					for (int i = 0; i < count; i++)
					{
						map.put(paths[i], Integer.valueOf(i));
					}
					rowIndex = map;
				}
			}
		}
		Integer i = map.get(ZkPaths.normalize(path));
		return i == null ? -1 : i.intValue();
	}

	/** 某路径的子节点数（来自索引，零网络）；不存在返回 -1 */
	public int childCount(String path)
	{
		int row = rowOf(path);
		return row < 0 ? -1 : numChildren[row];
	}

	public boolean isEphemeralPath(String path)
	{
		int row = rowOf(path);
		return row >= 0 && ephemeral[row];
	}

	/** 某路径的直接子节点路径（来自索引，零网络） */
	public List<String> childrenOf(String parent)
	{
		String p = ZkPaths.normalize(parent);
		String prefix = ZkPaths.ROOT.equals(p) ? "/" : p + "/";
		List<String> out = new ArrayList<String>();
		for (int i = 0; i < count; i++)
		{
			String candidate = paths[i];
			if (candidate.length() > prefix.length() && candidate.startsWith(prefix)
					&& candidate.indexOf('/', prefix.length()) < 0)
			{
				out.add(candidate);
			}
		}
		Collections.sort(out, ZkPaths.NATURAL);
		return out;
	}

	/** 该行归属的接口行；自身是接口则返回自身，不在接口下返回 -1 */
	public int interfaceOwnerOf(int row)
	{
		int[] owners = interfaceOwner;
		if (owners == null)
		{
			synchronized (this)
			{
				owners = interfaceOwner;
				if (owners == null)
				{
					owners = new int[count];
					java.util.Arrays.fill(owners, -2); // -2 = 尚未计算
					interfaceOwner = owners;
					for (int i = 0; i < count; i++)
					{
						resolveOwner(i, owners);
					}
				}
			}
		}
		return row >= 0 && row < count ? resolveOwner(row, owners) : -1;
	}

	private int resolveOwner(int row, int[] owners)
	{
		if (owners[row] != -2)
		{
			return owners[row];
		}
		if (Dubbo.isInterface(categories[row]))
		{
			owners[row] = row;
			return row;
		}
		// 路径深度很浅，递归带记忆化即可
		String parent = ZkPaths.parent(paths[row]);
		int parentRow = parent == null ? -1 : rowOf(parent);
		int owner = parentRow < 0 ? -1 : resolveOwner(parentRow, owners);
		owners[row] = owner;
		return owner;
	}

	/** 供应用内增删改后就地打补丁：返回新的不可变索引 */
	public PathIndex without(String pathPrefix)
	{
		String p = ZkPaths.normalize(pathPrefix);
		String prefix = p + "/";
		int keep = 0;
		for (int i = 0; i < count; i++)
		{
			if (!paths[i].equals(p) && !paths[i].startsWith(prefix))
			{
				keep++;
			}
		}
		if (keep == count)
		{
			return this;
		}
		String[] np = new String[keep];
		String[] nl = new String[keep];
		int[] ns = new int[keep];
		int[] nc = new int[keep];
		int[] dl = new int[keep];
		boolean[] ep = new boolean[keep];
		byte[] ct = new byte[keep];
		String[] nd = lowerData == null ? null : new String[keep];
		int j = 0;
		for (int i = 0; i < count; i++)
		{
			if (paths[i].equals(p) || paths[i].startsWith(prefix))
			{
				continue;
			}
			np[j] = paths[i];
			nl[j] = lower[i];
			ns[j] = nameStart[i];
			nc[j] = numChildren[i];
			dl[j] = dataLength[i];
			ep[j] = ephemeral[i];
			ct[j] = categories[i];
			if (nd != null)
			{
				nd[j] = lowerData[i];
			}
			j++;
		}
		return new PathIndex(np, nl, ns, nc, dl, ep, ct, nd, keep, dataIndexed, errors, builtAt,
				dataSkipped);
	}

	/** 用重新爬取的子树替换旧条目，单趟合并，O(n+m)。watch 到变更时局部刷新用 */
	public PathIndex replaceSubtree(String prefix, PathIndex sub)
	{
		String p = ZkPaths.normalize(prefix);
		String pre = p + "/";
		int keep = 0;
		for (int i = 0; i < count; i++)
		{
			if (!paths[i].equals(p) && !paths[i].startsWith(pre))
			{
				keep++;
			}
		}
		int newSize = keep + sub.count;
		String[] np = new String[newSize];
		String[] nl = new String[newSize];
		int[] ns = new int[newSize];
		int[] nc = new int[newSize];
		int[] dl = new int[newSize];
		boolean[] ep = new boolean[newSize];
		byte[] ct = new byte[newSize];
		String[] nd = lowerData == null && sub.lowerData == null ? null : new String[newSize];
		int j = 0;
		for (int i = 0; i < count; i++)
		{
			if (paths[i].equals(p) || paths[i].startsWith(pre))
			{
				continue;
			}
			np[j] = paths[i];
			nl[j] = lower[i];
			ns[j] = nameStart[i];
			nc[j] = numChildren[i];
			dl[j] = dataLength[i];
			ep[j] = ephemeral[i];
			ct[j] = categories[i];
			if (nd != null && lowerData != null)
			{
				nd[j] = lowerData[i];
			}
			j++;
		}
		for (int i = 0; i < sub.count; i++)
		{
			np[j] = sub.paths[i];
			nl[j] = sub.lower[i];
			ns[j] = sub.nameStart[i];
			nc[j] = sub.numChildren[i];
			dl[j] = sub.dataLength[i];
			ep[j] = sub.ephemeral[i];
			ct[j] = sub.categories[i];
			if (nd != null && sub.lowerData != null)
			{
				nd[j] = sub.lowerData[i];
			}
			j++;
		}
		return new PathIndex(np, nl, ns, nc, dl, ep, ct, nd, newSize,
				dataIndexed || sub.dataIndexed, errors, builtAt, dataSkipped);
	}

	/** 追加/覆盖若干节点（新建、stat 变化）。只用于少量补丁，不做大批量合并 */
	public PathIndex with(List<String> newPaths, List<NodeSnapshot> snapshots)
	{
		if (newPaths.isEmpty())
		{
			return this;
		}
		java.util.Map<String, Integer> existing = new java.util.HashMap<String, Integer>();
		for (int i = 0; i < count; i++)
		{
			existing.put(paths[i], i);
		}
		int added = 0;
		for (String raw : newPaths)
		{
			String p = ZkPaths.normalize(raw);
			if (!existing.containsKey(p))
			{
				existing.put(p, null);
				added++;
			}
		}
		int newSize = count + added;
		String[] np = java.util.Arrays.copyOf(paths, newSize);
		String[] nl = java.util.Arrays.copyOf(lower, newSize);
		int[] ns = java.util.Arrays.copyOf(nameStart, newSize);
		int[] nc = java.util.Arrays.copyOf(numChildren, newSize);
		int[] dl = java.util.Arrays.copyOf(dataLength, newSize);
		boolean[] ep = java.util.Arrays.copyOf(ephemeral, newSize);
		byte[] ct = java.util.Arrays.copyOf(categories, newSize);
		String[] nd = lowerData == null ? null : java.util.Arrays.copyOf(lowerData, newSize);
		int next = count;
		for (int i = 0; i < newPaths.size(); i++)
		{
			String p = ZkPaths.normalize(newPaths.get(i));
			NodeSnapshot snap = snapshots.get(i);
			Integer at = existing.get(p);
			int target;
			if (at == null)
			{
				target = next++;
				existing.put(p, target);
			}
			else
			{
				target = at.intValue();
			}
			np[target] = p;
			nl[target] = p.toLowerCase(Locale.ROOT);
			ns[target] = p.lastIndexOf('/') + 1;
			nc[target] = snap == null ? 0 : snap.numChildren;
			dl[target] = snap == null ? 0 : snap.dataLength;
			ep[target] = snap != null && snap.isEphemeral();
		}
		return new PathIndex(np, nl, ns, nc, dl, ep, ct, nd, newSize, dataIndexed, errors, builtAt,
				dataSkipped);
	}

	public boolean contains(String path)
	{
		return rowOf(path) >= 0;
	}

	/**
	 * 执行查询。命中按 4 档相关性排序：节点名精确 &gt; 名前缀 &gt; 名包含 &gt; 路径/数据包含。
	 * 扫描分块进行，凑够 100 条就先发布一次部分结果，界面立刻有反馈。
	 *
	 * @return 被取消时返回 null
	 */
	public Result search(String query, Mode mode, int limit, BooleanSupplier cancelled,
			Publisher publisher)
	{
		return search(query, mode, limit, cancelled, publisher, false);
	}

	/**
	 * 执行查询。命中按 4 档相关性排序：节点名精确 &gt; 名前缀 &gt; 名包含 &gt; 路径/数据包含。
	 * 扫描分块进行，凑够 100 条就先发布一次部分结果，界面立刻有反馈。
	 *
	 * <p>{@code groupInterfaces} 为 true 时，落在 Dubbo 接口下的命中会**在扫描过程中**
	 * 归并到接口节点并去重 —— 必须在截断之前归并，否则实例节点被 limit 截掉后
	 * 连接口都露不出来。不在任何接口下的命中保持原样，普通路径搜索不受影响。
	 *
	 * @return 被取消时返回 null
	 */
	public Result search(String query, Mode mode, int limit, BooleanSupplier cancelled,
			Publisher publisher, boolean groupInterfaces)
	{
		long t0 = System.nanoTime();
		if (query == null || query.isEmpty())
		{
			return new Result(Collections.<Hit> emptyList(), 0, false, 0, null);
		}
		if (count == 0)
		{
			return Result.error("Index is empty — build it first (Ctrl+I).");
		}
		if (mode == Mode.DATA && lowerData == null)
		{
			return Result.error("Content index not built. Tick “Data” to index node values.");
		}
		String needle = query.toLowerCase(Locale.ROOT);
		Pattern pattern = null;
		if (mode == Mode.GLOB)
		{
			pattern = Pattern.compile(globToRegex(query), Pattern.CASE_INSENSITIVE);
		}
		else if (mode == Mode.REGEX)
		{
			try
			{
				pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
			}
			catch (PatternSyntaxException e)
			{
				return Result.error("Invalid regex: " + e.getDescription());
			}
		}

		@SuppressWarnings("unchecked")
		List<Hit>[] tiers = new List[] { new ArrayList<Hit>(), new ArrayList<Hit>(),
				new ArrayList<Hit>(), new ArrayList<Hit>() };
		java.util.Set<Integer> seen = groupInterfaces ? new java.util.HashSet<Integer>() : null;
		int total = 0;
		int rawTotal = 0;
		boolean published = false;
		for (int i = 0; i < count; i++)
		{
			if ((i & (CHUNK - 1)) == 0 && cancelled != null && cancelled.getAsBoolean())
			{
				return null;
			}
			int tier = tierOf(i, mode, needle, pattern, query);
			if (tier < 0)
			{
				continue;
			}
			rawTotal++;
			int row = i;
			String matched = null;
			if (groupInterfaces)
			{
				int owner = interfaceOwnerOf(i);
				if (owner >= 0)
				{
					if (!seen.add(Integer.valueOf(owner)))
					{
						continue;
					}
					row = owner;
					if (row != i)
					{
						matched = paths[i];
					}
					// 接口名本身是否命中决定档位；只有后代命中时归到最低档
					int own = tierOf(row, mode, needle, pattern, query);
					tier = own >= 0 ? own : 3;
				}
			}
			total++;
			List<Hit> bucket = tiers[tier];
			if (bucket.size() < COLLECT_CAP)
			{
				Hit hit = new Hit(row, paths[row], nameStart[row], numChildren[row],
						dataLength[row], ephemeral[row], tier);
				hit.interfaceNode = groupInterfaces && isInterfaceRow(row);
				hit.matchedPath = matched;
				hit.snippetRow = i;
				bucket.add(hit);
			}
			if (!published && total >= 100 && publisher != null)
			{
				published = true;
				Result partial = new Result(collect(tiers, limit, groupInterfaces), total, true,
						(System.nanoTime() - t0) / 1000000L, null);
				partial.rawTotal = rawTotal;
				publisher.publish(partial);
			}
		}
		List<Hit> hits = collect(tiers, limit, groupInterfaces);
		if (mode == Mode.DATA)
		{
			fillSnippets(hits, needle);
		}
		Result result = new Result(hits, total, false, (System.nanoTime() - t0) / 1000000L, null);
		result.rawTotal = rawTotal;
		return result;
	}

	/** 聚合行补上 provider / consumer 数量，全部来自索引，零网络 */
	private void fillInterfaceCounts(List<Hit> hits)
	{
		for (Hit h : hits)
		{
			if (!h.interfaceNode)
			{
				continue;
			}
			h.providers = childCount(ZkPaths.join(h.path, Dubbo.PROVIDERS_NAME));
			h.consumers = childCount(ZkPaths.join(h.path, Dubbo.CONSUMERS_NAME));
		}
	}

	private int tierOf(int i, Mode mode, String needle, Pattern pattern, String rawQuery)
	{
		switch (mode)
		{
			case EXACT:
				return paths[i].equalsIgnoreCase(rawQuery) ? 0 : -1;
			case GLOB:
				return pattern.matcher(paths[i]).matches() ? 2 : -1;
			case REGEX:
				return pattern.matcher(paths[i]).find() ? 2 : -1;
			case DATA:
				String d = lowerData[i];
				return d != null && d.indexOf(needle) >= 0 ? 3 : -1;
			case NAME:
			{
				int at = lower[i].indexOf(needle, nameStart[i]);
				if (at < 0)
				{
					return -1;
				}
				return nameTier(i, needle, at);
			}
			case CONTAINS:
			default:
			{
				int at = lower[i].indexOf(needle, nameStart[i]);
				if (at >= 0)
				{
					return nameTier(i, needle, at);
				}
				return lower[i].indexOf(needle) >= 0 ? 3 : -1;
			}
		}
	}

	private int nameTier(int i, String needle, int at)
	{
		int start = nameStart[i];
		String lo = lower[i];
		if (lo.length() - start == needle.length() && at == start)
		{
			return 0;
		}
		return at == start ? 1 : 2;
	}

	private static final Comparator<Hit> RELEVANCE = new Comparator<Hit>()
	{
		@Override
		public int compare(Hit a, Hit b)
		{
			int c = Integer.compare(a.path.length(), b.path.length());
			return c != 0 ? c : ZkPaths.NATURAL.compare(a.path, b.path);
		}
	};

	private List<Hit> collect(List<Hit>[] tiers, int limit, boolean groupInterfaces)
	{
		List<Hit> out = new ArrayList<Hit>(Math.min(limit, 512));
		for (List<Hit> tier : tiers)
		{
			if (out.size() >= limit)
			{
				break;
			}
			Collections.sort(tier, RELEVANCE);
			for (Hit h : tier)
			{
				out.add(h);
				if (out.size() >= limit)
				{
					break;
				}
			}
		}
		if (groupInterfaces)
		{
			fillInterfaceCounts(out);
		}
		return out;
	}

	private void fillSnippets(List<Hit> hits, String needle)
	{
		for (Hit h : hits)
		{
			String d = lowerData[h.snippetRow];
			if (d == null)
			{
				continue;
			}
			int at = d.indexOf(needle);
			if (at < 0)
			{
				continue;
			}
			int from = Math.max(0, at - 14);
			int to = Math.min(d.length(), at + needle.length() + 22);
			h.snippet = (from > 0 ? "…" : "") + d.substring(from, to).replace('\n', ' ')
					+ (to < d.length() ? "…" : "");
		}
	}

	/** glob 转正则：* 跨 / 匹配，? 匹配单字符，其余字符按字面量 */
	static String globToRegex(String glob)
	{
		StringBuilder sb = new StringBuilder(glob.length() + 16);
		for (int i = 0; i < glob.length(); i++)
		{
			char c = glob.charAt(i);
			switch (c)
			{
				case '*':
					sb.append(".*");
					break;
				case '?':
					sb.append('.');
					break;
				case '.':
				case '(':
				case ')':
				case '+':
				case '|':
				case '^':
				case '$':
				case '@':
				case '%':
				case '{':
				case '}':
				case '[':
				case ']':
				case '\\':
					sb.append('\\').append(c);
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}
}