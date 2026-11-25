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

import org.apache.zookeeper.data.Stat;

import com.zooinspector.util.ZkPaths;

/**
 * znode 元数据的不可变快照。树模型与搜索索引都只读它，
 * 不再像老版那样每次 Swing 询问都发一次网络请求。
 */
public final class NodeSnapshot
{
	public final String path;
	public final String name;
	public final long czxid;
	public final long mzxid;
	public final long ctime;
	public final long mtime;
	public final int version;
	public final int cversion;
	public final int aversion;
	public final long ephemeralOwner;
	public final int dataLength;
	public final int numChildren;
	public final long pzxid;

	public NodeSnapshot(String path, Stat stat)
	{
		this.path = ZkPaths.normalize(path);
		this.name = ZkPaths.name(this.path);
		if (stat == null)
		{
			this.czxid = 0;
			this.mzxid = 0;
			this.ctime = 0;
			this.mtime = 0;
			this.version = 0;
			this.cversion = 0;
			this.aversion = 0;
			this.ephemeralOwner = 0;
			this.dataLength = 0;
			this.numChildren = 0;
			this.pzxid = 0;
		}
		else
		{
			this.czxid = stat.getCzxid();
			this.mzxid = stat.getMzxid();
			this.ctime = stat.getCtime();
			this.mtime = stat.getMtime();
			this.version = stat.getVersion();
			this.cversion = stat.getCversion();
			this.aversion = stat.getAversion();
			this.ephemeralOwner = stat.getEphemeralOwner();
			this.dataLength = stat.getDataLength();
			this.numChildren = stat.getNumChildren();
			this.pzxid = stat.getPzxid();
		}
	}

	public boolean isEphemeral()
	{
		return ephemeralOwner != 0;
	}

	public boolean hasChildren()
	{
		return numChildren > 0;
	}

	/** sequential 节点无法从 stat 判定，用 ZooKeeper 固定的 10 位数字后缀启发式识别 */
	public boolean looksSequential()
	{
		if (name.length() < 10)
		{
			return false;
		}
		for (int i = name.length() - 10; i < name.length(); i++)
		{
			if (!Character.isDigit(name.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}

	public Stat toStat()
	{
		Stat s = new Stat();
		s.setCzxid(czxid);
		s.setMzxid(mzxid);
		s.setCtime(ctime);
		s.setMtime(mtime);
		s.setVersion(version);
		s.setCversion(cversion);
		s.setAversion(aversion);
		s.setEphemeralOwner(ephemeralOwner);
		s.setDataLength(dataLength);
		s.setNumChildren(numChildren);
		s.setPzxid(pzxid);
		return s;
	}
}