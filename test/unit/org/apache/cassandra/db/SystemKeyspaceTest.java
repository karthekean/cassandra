/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.dht.ByteOrderedPartitioner.BytesToken;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.SemanticVersion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SystemKeyspaceTest
{
    @Test
    public void testLocalTokens()
    {
        // Remove all existing tokens
        Collection<Token> current = SystemKeyspace.loadTokens().asMap().get(FBUtilities.getLocalAddress());
        if (current != null && !current.isEmpty())
            SystemKeyspace.updateTokens(current);

        List<Token> tokens = new ArrayList<Token>()
        {{
            for (int i = 0; i < 9; i++)
                add(new BytesToken(ByteBufferUtil.bytes(String.format("token%d", i))));
        }};

        SystemKeyspace.updateTokens(tokens);
        int count = 0;

        for (Token tok : SystemKeyspace.getSavedTokens())
            assert tokens.get(count++).equals(tok);
    }

    @Test
    public void testNonLocalToken() throws UnknownHostException
    {
        BytesToken token = new BytesToken(ByteBufferUtil.bytes("token3"));
        InetAddress address = InetAddress.getByName("127.0.0.2");
        SystemKeyspace.updateTokens(address, Collections.<Token>singletonList(token));
        assert SystemKeyspace.loadTokens().get(address).contains(token);
        SystemKeyspace.removeEndpoint(address);
        assert !SystemKeyspace.loadTokens().containsValue(token);
    }

    @Test
    public void testLocalHostID()
    {
        UUID firstId = SystemKeyspace.getLocalHostId();
        UUID secondId = SystemKeyspace.getLocalHostId();
        assert firstId.equals(secondId) : String.format("%s != %s%n", firstId.toString(), secondId.toString());
    }

    @Test
    public void snapshotSystemKeyspaceIfUpgrading() throws IOException
    {
        // First, check that in the absence of any previous installed version, we don't create snapshots
        for (ColumnFamilyStore cfs : Keyspace.open(SystemKeyspace.NAME).getColumnFamilyStores())
            cfs.clearUnsafe();
        Keyspace.clearSnapshot(null, SystemKeyspace.NAME);

        SystemKeyspace.snapshotOnVersionChange();
        assertTrue(getSystemSnapshotFiles().isEmpty());

        // now setup system.local as if we're upgrading from a previous version
        SemanticVersion next = getCurrentReleaseVersion();
        setupReleaseVersion(new SemanticVersion(String.format("%s.%s.%s", next.major - 1, next.minor, next.patch)));
        Keyspace.clearSnapshot(null, SystemKeyspace.NAME);
        assertTrue(getSystemSnapshotFiles().isEmpty());

        // Compare versions again & verify that snapshots were created for all tables in the system ks
        SystemKeyspace.snapshotOnVersionChange();
        assertEquals(SystemKeyspace.definition().cfMetaData().size(), getSystemSnapshotFiles().size());

        // clear out the snapshots & set the previous recorded version equal to the latest, we shouldn't
        // see any new snapshots created this time.
        Keyspace.clearSnapshot(null, SystemKeyspace.NAME);
        setupReleaseVersion(getCurrentReleaseVersion());

        SystemKeyspace.snapshotOnVersionChange();
        assertTrue(getSystemSnapshotFiles().isEmpty());
    }

    private SemanticVersion getCurrentReleaseVersion()
    {
        return new SemanticVersion(FBUtilities.getReleaseVersionString());
    }

    private Set<String> getSystemSnapshotFiles()
    {
        Set<String> snapshottedTableNames = new HashSet<>();
        for (ColumnFamilyStore cfs : Keyspace.open(SystemKeyspace.NAME).getColumnFamilyStores())
        {
            if (!cfs.getSnapshotDetails().isEmpty())
                snapshottedTableNames.add(cfs.getColumnFamilyName());
        }
        return snapshottedTableNames;
    }

    private void setupReleaseVersion(SemanticVersion version)
    {
        // besides the release_version, we also need to insert the cluster_name or the check
        // in SystemKeyspace.checkHealth were we verify it matches DatabaseDescriptor will fail
        QueryProcessor.executeInternal(String.format("INSERT INTO system.local(key, release_version, cluster_name) " +
                                                     "VALUES ('local', '%s', '%s')",
                                                     version,
                                                     DatabaseDescriptor.getClusterName()));
        String r = readLocalVersion();
        assertEquals(String.format("Expected %s, got %s", version, r), version.toString(), r);
    }

    private String readLocalVersion()
    {
        UntypedResultSet rs = QueryProcessor.executeInternal("SELECT release_version FROM system.local WHERE key='local'");
        return rs.isEmpty() || !rs.one().has("release_version") ? null : rs.one().getString("release_version");
    }
}
