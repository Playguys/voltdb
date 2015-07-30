/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb;

import junit.framework.TestCase;

import org.voltdb.VoltDB.Configuration;
import org.voltdb.client.Client;
import org.voltdb.client.ClientFactory;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.utils.MiscUtils;
import org.voltdb.utils.MockStatsProducer;
import org.voltdb.utils.SystemStatsCollector;
import org.voltdb.utils.SystemStatsCollector.Datum;

public class TestResourceMonitor extends TestCase
{
    private ServerThread m_localServer;
    private VoltDB.Configuration m_config;
    private Client m_client;
    private TestStatsProducer m_mockStatsProducer;
    private final int m_monitoringInterval = 2;

    public void setUpServer(boolean setRssLimit) throws Exception
    {
        VoltProjectBuilder builder = new VoltProjectBuilder();
        if (setRssLimit) {
            builder.setRssLimit(1);
        }
        boolean success = builder.compile(Configuration.getPathToCatalogForTest("resourcemonitor.jar"), 1, 1, 0);
        assert(success);
        MiscUtils.copyFile(builder.getPathToDeployment(), Configuration.getPathToCatalogForTest("resourcemonitor.xml"));

        // set up the interval for resource limit monitoring
        System.setProperty(RealVoltDB.RESOURCE_MONITOR_INTERVAL, Integer.toString(m_monitoringInterval));


        m_config = new VoltDB.Configuration();
        m_config.m_pathToCatalog = Configuration.getPathToCatalogForTest("resourcemonitor.jar");
        m_config.m_pathToDeployment = Configuration.getPathToCatalogForTest("resourcemonitor.xml");
        m_localServer = new ServerThread(m_config);
        m_localServer.start();
        m_localServer.waitForInitialization();

        m_mockStatsProducer = new TestStatsProducer();
        SystemStatsCollector.setMockStatsProducer(m_mockStatsProducer);

        assertEquals(OperationMode.RUNNING, VoltDB.instance().getMode());
        m_client = ClientFactory.createClient();
        m_client.createConnection("localhost:" + m_config.m_adminPort);
    }

    @Override
    public void tearDown() throws Exception {
        m_client.close();
        m_localServer.shutdown();
    }

    public void testNoRssLimitChecking() throws Exception
    {
        setUpServer(false);

        // Wait for monitoring interval time and verify server is still in running mode
        m_mockStatsProducer.m_rss = 2048*1024*1024;
        resumeAndWait(m_monitoringInterval+1);
        assertEquals(OperationMode.RUNNING, VoltDB.instance().getMode());
    }

    public void testLimitNotExceeded() throws Exception
    {
        setUpServer(true);

        m_mockStatsProducer.m_rss = 10*1024*1024;
        assertEquals(m_mockStatsProducer.m_rss, SystemStatsCollector.getRSSMB());

        // Wait for monitoring interval time and verify server is still in running mode
        resumeAndWait(m_monitoringInterval+1);
        assertEquals(OperationMode.RUNNING, VoltDB.instance().getMode());
    }

    public void testLimitExceededWithResumePauseAgain() throws Exception
    {
        setUpServer(true);

        // Go above limit, wait for more than configured amt of time and verify server is paused
        m_mockStatsProducer.m_rss = 1024*1024*1024;
        resumeAndWait(m_monitoringInterval+1);
        assertEquals(OperationMode.PAUSED, VoltDB.instance().getMode());

        // Resume and verify that server again goes into paused.
        m_client.callProcedure("@Resume");
        resumeAndWait(m_monitoringInterval+1);
        assertEquals(OperationMode.PAUSED, VoltDB.instance().getMode());
    }

    public void testLimitExceededWithResume() throws Exception
    {
        setUpServer(true);

        // Go above limit, wait for more than configured amt of time and verify server is paused
        m_mockStatsProducer.m_rss = 1024*1024*1024;
        resumeAndWait(m_monitoringInterval+1);
        assertEquals(OperationMode.PAUSED, VoltDB.instance().getMode());

        // Don't go above limit in mock, resume and make sure server does not go back into paused.
        m_mockStatsProducer.m_rss = 1024*1024;
        resumeAndWait(m_monitoringInterval+1);
        assertEquals(OperationMode.RUNNING, VoltDB.instance().getMode());
    }

    // time in seconds
    private void resumeAndWait(long time) throws Exception
    {
        // first wait for system stats collector interval of 5 seconds
        // to make sure that a stats collection is run.
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis()-startTime < 5000) {
            try { Thread.sleep(5000); } catch(InterruptedException e) { }
        }

        m_client.callProcedure("@Resume");

        // now sleep for specified time
        startTime = System.currentTimeMillis();
        while (System.currentTimeMillis()-startTime < time*1000) {
            try { Thread.sleep(time*1000); } catch(InterruptedException e) { }
        }
    }

    private static class TestStatsProducer implements MockStatsProducer
    {
        volatile long m_rss;

        @Override
        public Datum getCurrentStatsData()
        {
            return new Datum(m_rss);
        }
    }
}
