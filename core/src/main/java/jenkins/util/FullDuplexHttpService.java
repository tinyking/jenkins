/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.util;

import hudson.cli.FullDuplexHttpStream;
import hudson.util.ChunkedInputStream;
import hudson.util.ChunkedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * Server-side counterpart to {@link FullDuplexHttpStream}.
 * @since 2.54
 */
public abstract class FullDuplexHttpService {

    /**
     * Set to true if the servlet container doesn't support chunked encoding.
     */
    @Restricted(NoExternalUse.class)
    public static boolean DIY_CHUNKING = SystemProperties.getBoolean("hudson.diyChunking");

    /**
     * Controls the time out of waiting for the 2nd HTTP request to arrive.
     */
    @Restricted(NoExternalUse.class)
    public static long CONNECTION_TIMEOUT = TimeUnit.SECONDS.toMillis(15);

    protected final UUID uuid;

    private InputStream upload;

    private boolean completed;

    protected FullDuplexHttpService(UUID uuid) {
        this.uuid = uuid;
    }

    /**
     * This is where we send the data to the client.
     *
     * <p>
     * If this connection is lost, we'll abort the channel.
     */
    public synchronized void download(StaplerRequest req, StaplerResponse rsp) throws InterruptedException, IOException {
        rsp.setStatus(HttpServletResponse.SC_OK);

        // server->client channel.
        // this is created first, and this controls the lifespan of the channel
        rsp.addHeader("Transfer-Encoding", "chunked");
        OutputStream out = rsp.getOutputStream();
        if (DIY_CHUNKING) {
            out = new ChunkedOutputStream(out);
        }

        // send something out so that the client will see the HTTP headers
        out.write(0);
        out.flush();

        {// wait until we have the other channel
            long end = System.currentTimeMillis() + CONNECTION_TIMEOUT;
            while (upload == null && System.currentTimeMillis() < end) {
                wait(1000);
            }

            if (upload == null) {
                throw new IOException("HTTP full-duplex channel timeout: " + uuid);
            }
        }

        try {
            run(upload, out);
        } finally {
            // publish that we are done
            completed = true;
            notify();
        }
    }

    protected abstract void run(InputStream upload, OutputStream download) throws IOException, InterruptedException;

    /**
     * This is where we receive inputs from the client.
     */
    public synchronized void upload(StaplerRequest req, StaplerResponse rsp) throws InterruptedException, IOException {
        rsp.setStatus(HttpServletResponse.SC_OK);
        InputStream in = req.getInputStream();
        if (DIY_CHUNKING) {
            in = new ChunkedInputStream(in);
        }

        // publish the upload channel
        upload = in;
        notify();

        // wait until we are done
        while (!completed) {
            wait();
        }
    }

    /**
     * HTTP response that allows a client to use this service.
     */
    public static abstract class Response extends HttpResponses.HttpResponseException {

        private final Map<UUID, FullDuplexHttpService> services;

        /**
         * @param services a cross-request cache of services, to correlate the
         * upload and download connections
         */
        protected Response(Map<UUID, FullDuplexHttpService> services) {
            this.services = services;
        }

        @Override
        public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {
            try {
                // do not require any permission to establish a CLI connection
                // the actual authentication for the connecting Channel is done by CLICommand

                UUID uuid = UUID.fromString(req.getHeader("Session"));
                rsp.setHeader("Hudson-Duplex", "true"); // set the header so that the client would know

                if (req.getHeader("Side").equals("download")) {
                    FullDuplexHttpService service = createService(req, uuid);
                    services.put(uuid, service);
                    try {
                        service.download(req, rsp);
                    } finally {
                        services.remove(uuid);
                    }
                } else {
                    services.get(uuid).upload(req, rsp);
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        protected abstract FullDuplexHttpService createService(StaplerRequest req, UUID uuid) throws IOException, InterruptedException;

    }

}
