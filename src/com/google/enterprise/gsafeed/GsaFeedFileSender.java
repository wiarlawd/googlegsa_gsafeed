// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.gsafeed;

import static java.util.Locale.US;

import com.google.common.annotations.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLException;

/** Takes an XML feed file for the GSA, sends it to GSA and
  then reads reply from GSA. */
// Modified from GsaFeedFileSender in adaptor library.
public class GsaFeedFileSender {
  private static final Logger log
      = Logger.getLogger(GsaFeedFileSender.class.getName());
  private static final Pattern DATASOURCE_FORMAT
      = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]*");
  private static final Pattern GROUPSOURCE_FORMAT
      = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]*");

  // Feed file XML will not contain "<<".
  private static final String BOUNDARY = "<<";

  // Another frequently used constant of sent message.
  private static final String CRLF = "\r\n";

  private Charset gsaCharEncoding;
  private URL feedDest;
  private URL groupsDest;

  private static URL makeHandlerUrl(String host, boolean secure, String path) {
    if (null == host || null == path) {
      throw new NullPointerException();
    }
    try {
      if (secure) {
        return new URL("https://" + host + ":19902/" + path);
      } else {
        return new URL("http://" + host + ":19900/" + path);
      }
    } catch (MalformedURLException mue) {
      throw new IllegalArgumentException("invalid url", mue);
    }
  }

  public GsaFeedFileSender(String host, boolean secure, Charset gsaCharSet) {
    this(makeHandlerUrl(host, secure, "xmlfeed"),
        makeHandlerUrl(host, secure, "xmlgroups"), gsaCharSet);
  }

  @VisibleForTesting
  GsaFeedFileSender(URL feedUrl, URL groupsUrl, Charset gsaCharSet) {
    if (null == gsaCharSet) {
      throw new NullPointerException();
    }
    feedDest = feedUrl;
    groupsDest = groupsUrl;
    gsaCharEncoding = gsaCharSet;
  }

  // Get bytes of string in communication's encoding.
  private byte[] toEncodedBytes(String s) {
    return s.getBytes(gsaCharEncoding);
  }

  /** Helper method for creating a multipart/form-data HTTP post.
    Creates a post parameter made of a name and value. */
  private void buildPostParameter(StringBuilder sb, String name,
      String mimetype, String value) {
    sb.append("--").append(BOUNDARY).append(CRLF);
    sb.append("Content-Disposition: form-data;");
    sb.append(" name=\"").append(name).append("\"").append(CRLF);
    sb.append("Content-Type: ").append(mimetype).append(CRLF);
    sb.append(CRLF).append(value).append(CRLF);
  }

  private byte[] buildGsaFeedMessage(String datasource,
      String feedtype, String xmlDocument) {
    StringBuilder sb = new StringBuilder();
    buildPostParameter(sb, "datasource", "text/plain", datasource);
    buildPostParameter(sb, "feedtype", "text/plain", feedtype);
    buildPostParameter(sb, "data", "text/xml", xmlDocument);
    sb.append("--").append(BOUNDARY).append("--").append(CRLF);
    return toEncodedBytes("" + sb);
  }

  /**
   * Builds the multipart HTTP message for uploading group definitions.
   * @throws NullPointerException if feedtype is null.
   */
  private byte[] buildGroupsXmlMessage(String groupsource, String feedtype,
      String xmlDocument) {
    StringBuilder sb = new StringBuilder();
    String ft = feedtype.toLowerCase(US);
    if (ft.equals("full") || ft.equals("incremental")) {
      buildPostParameter(sb, "groupsource", "text/plain", groupsource);
      buildPostParameter(sb, "feedtype", "text/plain", feedtype);
      buildPostParameter(sb, "data", "text/xml", xmlDocument);
    } else if (ft.equals("cleanup")) {
      buildPostParameter(sb, "cleanup", "text/plain", groupsource);
    } else if (ft.equals("replace")) {
      buildPostParameter(sb, "replace", "text/plain", groupsource);
      buildPostParameter(sb, "data", "text/xml", xmlDocument);
    } else {
      throw new IllegalArgumentException("invalid feedtype: " + feedtype);
    }
    sb.append("--").append(BOUNDARY).append("--").append(CRLF);
    return toEncodedBytes("" + sb);
  }

  /** Tries to get in touch with our GSA. */
  private HttpURLConnection setupConnection(URL url, int len,
                                            boolean useCompression)
      throws IOException {
    HttpURLConnection uc = (HttpURLConnection) url.openConnection();
    uc.setDoInput(true);
    uc.setDoOutput(true);
    if (useCompression) {
      uc.setChunkedStreamingMode(0);
      // GSA can handle gziped content, although there isn't a way to find out
      // other than just trying
      uc.setRequestProperty("Content-Encoding", "gzip");
    } else {
      uc.setFixedLengthStreamingMode(len);
    }
    uc.setRequestProperty("Content-Type",
        "multipart/form-data; boundary=" + BOUNDARY);
    return uc;
  }

  /** Put bytes onto output stream. */
  private void writeToGsa(HttpURLConnection uc, byte msgbytes[],
                          boolean useCompression)
      throws IOException {
    OutputStream outputStream = uc.getOutputStream();
    try {
      if (useCompression) {
        // setupConnection set Content-Encoding: gzip
        outputStream = new GZIPOutputStream(outputStream);
      }
      // Use copyStream(), because using a single write() prevents errors from
      // propagating during writing and causes them to be discovered at read
      // time. Using copyStream() isn't perfect either though, in that if
      // buffered data eventually causes an error, then that will still be
      // discovered at read time.
      IOHelper.copyStream(new ByteArrayInputStream(msgbytes), outputStream);
      outputStream.flush();
    } finally {
      outputStream.close();
    }
  }

  /** Get GSA's response. */
  private String readGsaReply(HttpURLConnection uc) throws IOException {
    InputStream inputStream;
    try {
      inputStream = uc.getInputStream();
    } catch (IOException ioe) {
      inputStream = uc.getErrorStream();
    }
    if (null == inputStream) {
      return null;
    }
    String reply;
    try {
      reply = IOHelper.readInputStreamToString(inputStream, gsaCharEncoding);
    } finally {
      inputStream.close();
    }
    return reply;
  }

  private void handleGsaReply(String reply, int responseCode) {
    if ("Success".equals(reply) || "success".equals(reply)) {
      log.info("success message received. code:" + responseCode);
    } else if ("Error - Unauthorized Request".equals(reply)) {
      throw new IllegalStateException("Unauthorized request. "
          + "Perhaps add this machine's IP to GSA's Feeds' list "
          + "of trusted IP addresses. code:" + responseCode);
    } else {
      String msg = "HTTP code: " + responseCode + " body: " + reply;
      throw new IllegalStateException(msg);
    }
    // if ("Internal Error".equals(reply))
  }

  /**
   * Sends XML with provided datasource name and feedtype.
   * Datasource name is limited to [a-zA-Z_][a-zA-Z0-9_-]*.
   */
  public void sendGsaFeed(String datasource, String feedtype, String xmlString,
      boolean useCompression) throws IOException {
    if (!DATASOURCE_FORMAT.matcher(datasource).matches()) {
      throw new IllegalArgumentException("Data source contains illegal "
          + "characters: " + datasource);
    }
    byte msg[] = buildGsaFeedMessage(datasource, feedtype, xmlString);
    // GSA only allows request content up to 1 MB to be compressed
    if (msg.length >= 1 * 1024 * 1024) {
      useCompression = false;
    }
    sendMessage(feedDest, msg, useCompression);
  }

  /**
   * Sends XML with provided groupsource name to xmlgroups recipient.
   * Groupsource name is limited to [a-zA-Z_][a-zA-Z0-9_-]*.
   */
  public void sendGroups(String groupsource, String feedtype, String xmlString,
      boolean useCompression) throws IOException {
    if (!GROUPSOURCE_FORMAT.matcher(groupsource).matches()) {
      throw new IllegalArgumentException("Group source is invalid: "
          + groupsource);
    }
    byte msg[] = buildGroupsXmlMessage(groupsource, feedtype, xmlString);
    // GSA only allows request content up to 1 MB to be compressed
    if (msg.length >= 1 * 1024 * 1024) {
      useCompression = false;
    }
    sendMessage(groupsDest, msg, useCompression);
  }

  private void sendMessage(URL destUrl, byte msg[], boolean useCompression)
      throws IOException {
    HttpURLConnection uc;
    try {
      uc = setupConnection(destUrl, msg.length, useCompression);
      uc.connect();
    } catch (IOException ioe) {
      throw handleGsaException(destUrl.toString(), ioe);
    }
    try {
      writeToGsa(uc, msg, useCompression);
      String reply = readGsaReply(uc);
      handleGsaReply(reply, uc.getResponseCode());
    } catch (IOException ioe) {
      uc.disconnect();
      throw ioe;
    }
  }

  /** Wrap certain GSA communication problems with more descriptive messages. */
  /* Method copied from GsaCommunicationHandler in adaptor library. */
  static IOException handleGsaException(String gsa, IOException e) {
    if (e instanceof ConnectException) {
      return new IOException("Failed to connect to the GSA at " + gsa + " . "
          + "Please verify that the gsa.hostname configuration property "
          + "is correct and the GSA is online, and is configured to accept "
          + "feeds from this computer.", e);
    } else if (e instanceof UnknownHostException) {
      return new IOException("Failed to locate the GSA at " + gsa + " . "
          + "Please verify that the gsa.hostname configuration property "
          + "is correct.", e);
    } else if (e instanceof SSLException) {
      return new IOException("Failed to connect to the GSA at " + gsa + " . "
          + "Please verify that the your SSL Certificates are properly "
          + "configured for secure communication with the GSA.", e);
    } else {
      return e;
    }
  }
}
