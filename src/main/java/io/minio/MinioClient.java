/*
 * Minio Java Library for Amazon S3 Compatible Cloud Storage, (C) 2015 Minio, Inc.
 *
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

package io.minio;

import io.minio.acl.Acl;
import io.minio.errors.*;
import io.minio.messages.*;
import io.minio.http.*;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.Headers;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import com.google.api.client.xml.Xml;
import com.google.api.client.xml.XmlNamespaceDictionary;
import com.google.common.io.BaseEncoding;
import org.apache.commons.validator.routines.InetAddressValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.io.Reader;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.security.InvalidKeyException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * <p>
 * This class implements a simple cloud storage client. This client consists
 * of a useful subset of S3 compatible functionality.
 * </p>
 * <h2>Service</h2>
 * <ul>
 * <li>Creating a bucket</li>
 * <li>Listing buckets</li>
 * </ul>
 * <h2>Bucket</h2>
 * <ul>
 * <li> Creating an object, including automatic upload resuming for large objects.</li>
 * <li> Listing objects in a bucket</li>
 * <li> Listing active multipart uploads</li>
 * <li> Setting canned ACLs on buckets</li>
 * </ul>
 * <h2>Object</h2>
 * <ul>
 * <li>Removing an active multipart upload for a specific object and uploadId</li>
 * <li>Read object metadata</li>
 * <li>Reading an object</li>
 * <li>Reading a range of bytes of an object</li>
 * <li>Deleting an object</li>
 * </ul>
 * <p>
 * Optionally, users can also provide access/secret keys. If keys are provided, all requests by the
 * client will be signed using AWS Signature Version 4.
 * </p>
 * For examples on using this library, please see
 * <a href="https://github.com/minio/minio-java/tree/master/src/test/java/io/minio/examples"></a>.
 */
@SuppressWarnings({"SameParameterValue", "WeakerAccess"})
public final class MinioClient {
  private static final DateTimeFormatter AMZ_DATE_FORMAT = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZoneUTC();
  // default multipart upload size is 5MiB, maximum is 5GiB
  private static final int MIN_MULTIPART_SIZE = 5 * 1024 * 1024;
  private static final int MAX_MULTIPART_SIZE = 5 * 1024 * 1024 * 1024;
  // default expiration for a presigned URL is 7 days in seconds
  private static final int DEFAULT_EXPIRY_TIME = 7 * 24 * 3600;
  private static final String DEFAULT_USER_AGENT = "Minio (" + System.getProperty("os.arch") + "; "
      + System.getProperty("os.arch") + ") minio-java/" + MinioProperties.INSTANCE.getVersion();

  // the current client instance's base URL.
  private HttpUrl url;
  // access key to sign all requests with
  private String accessKey;
  // Secret key to sign all requests with
  private String secretKey;

  // default Transport
  private final OkHttpClient transport = new OkHttpClient();

  // logger which is set only on enableLogger. Atomic reference is used to prevent multiple loggers
  // from being instantiated
  private final AtomicReference<Logger> logger = new AtomicReference<Logger>();
  private String userAgent = DEFAULT_USER_AGENT;


  public MinioClient(String endpoint) throws MinioException {
    this(endpoint, 0, null, null, false);
  }


  public MinioClient(URL url) throws NullPointerException, MinioException {
    this(url.toString(), 0, null, null, false);
  }


  public MinioClient(String endpoint, String accessKey, String secretKey) throws MinioException {
    this(endpoint, 0, accessKey, secretKey, false);
  }


  public MinioClient(URL url, String accessKey, String secretKey) throws NullPointerException, MinioException {
    this(url.toString(), 0, accessKey, secretKey, false);
  }


  public MinioClient(String endpoint, int port, String accessKey, String secretKey) throws MinioException {
    this(endpoint, port, accessKey, secretKey, false);
  }


  public MinioClient(String endpoint, String accessKey, String secretKey, boolean insecure) throws MinioException {
    this(endpoint, 0, accessKey, secretKey, insecure);
  }


  /**
   * Create a new client.
   *
   * @param endpoint  request endpoint.  Valid endpoint is an URL, domain name, IPv4 or IPv6 address.
   *                  Valid endpoints:
   *                  * https://s3.amazonaws.com
   *                  * https://s3.amazonaws.com/
   *                  * https://play.minio.io:9000
   *                  * http://play.minio.io:9010/
   *                  * localhost
   *                  * localhost.localdomain
   *                  * play.minio.io
   *                  * 127.0.0.1
   *                  * 192.168.1.60
   *                  * ::1
   * @param port      valid port.  It should be in between 1 and 65535.  Unused if endpoint is an URL.
   * @param accessKey access key to access service in endpoint.
   * @param secretKey secret key to access service in endpoint.
   * @param insecure  to access endpoint, use HTTP if true else HTTPS.
   *
   * @see #MinioClient(String endpoint)
   * @see #MinioClient(URL url)
   * @see #MinioClient(String endpoint, String accessKey, String secretKey)
   * @see #MinioClient(URL url, String accessKey, String secretKey)
   * @see #MinioClient(String endpoint, int port, String accessKey, String secretKey)
   * @see #MinioClient(String endpoint, String accessKey, String secretKey, boolean insecure)
   */
  public MinioClient(String endpoint, int port, String accessKey, String secretKey, boolean insecure)
    throws MinioException {
    if (endpoint == null) {
      throw new MinioException("null endpoint");
    }

    // for valid URL endpoint, port and insecure are ignored
    HttpUrl url = HttpUrl.parse(endpoint);
    if (url != null) {
      if (!"/".equals(url.encodedPath())) {
        throw new MinioException("no path allowed in endpoint '" + endpoint + "'");
      }

      // treat Amazon S3 host as special case
      String amzHost = url.host();
      if (amzHost.endsWith(".amazonaws.com") && !amzHost.equals("s3.amazonaws.com")) {
        throw new MinioException("for Amazon S3, host should be 's3.amazonaws.com' in endpoint '" + endpoint + "'");
      }

      this.url = url;
      this.accessKey = accessKey;
      this.secretKey = secretKey;

      return;
    }

    // endpoint may be a valid hostname, IPv4 or IPv6 address
    if (!this.isValidEndpoint(endpoint)) {
      throw new MinioException("invalid host '" + endpoint + "'");
    }

    // treat Amazon S3 host as special case
    if (endpoint.endsWith(".amazonaws.com") && !endpoint.equals("s3.amazonaws.com")) {
      throw new MinioException("unsupported host '" + endpoint
                                 + "'.  For amazon S3, host should be 's3.amazonaws.com'");
    }

    if (port < 0 || port > 65535) {
      throw new MinioException("port must be in range of 1 to 65535");
    }

    Scheme scheme = Scheme.HTTPS;
    if (insecure) {
      scheme = Scheme.HTTP;
    }

    if (port == 0) {
      this.url = new HttpUrl.Builder()
          .scheme(scheme.toString())
          .host(endpoint)
          .build();
    } else {
      this.url = new HttpUrl.Builder()
          .scheme(scheme.toString())
          .host(endpoint)
          .port(port)
          .build();
    }
    this.accessKey = accessKey;
    this.secretKey = secretKey;
  }


  private boolean isValidEndpoint(String endpoint) {
    if (InetAddressValidator.getInstance().isValid(endpoint)) {
      return true;
    }

    // endpoint may be a hostname
    // refer https://en.wikipedia.org/wiki/Hostname#Restrictions_on_valid_host_names
    // why checks are done like below
    if (endpoint.length() < 1 || endpoint.length() > 253) {
      return false;
    }

    for (String label : endpoint.split("\\.")) {
      if (label.length() < 1 || label.length() > 63) {
        return false;
      }

      if (!(label.matches("^[a-zA-Z0-9][a-zA-Z0-9-]*") && endpoint.matches(".*[a-zA-Z0-9]$"))) {
        return false;
      }
    }

    return true;
  }


  private Request getRequest(Method method, HttpUrl url, final byte[] data) {
    if (url == null) {
      return null;
    }

    DateTime date = new DateTime();
    this.transport.setFollowRedirects(false);
    this.transport.interceptors().add(new RequestSigner(data, accessKey, secretKey, date));

    RequestBody requestBody = null;
    if (data != null) {
      requestBody = RequestBody.create(null, data);
    }

    Request request = new Request.Builder()
        .url(url)
        .method(method.toString(), requestBody)
        .header("User-Agent", this.userAgent)
        .header("x-amz-date", date.toString(AMZ_DATE_FORMAT))
        .build();

    return request;
  }


  private Request getGetRequest(HttpUrl url) {
    return getRequest(Method.GET, url, null);
  }


  private Request getPutRequest(HttpUrl url, final byte[] data) {
    return getRequest(Method.PUT, url, data);
  }


  private void executeGet(HttpUrl url, XmlEntity entity)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException {
    Request request = getRequest(Method.GET, url, null);
    Response response = this.transport.newCall(request).execute();

    if (response == null) {
      throw new NoResponseException();
    }

    Reader bodyStream = response.body().charStream();

    try {
      if (!response.isSuccessful()) {
        throw new ErrorResponseException(new ErrorResponse(bodyStream));
      } else {
        entity.parseXml(bodyStream);
      }
    } finally {
      response.body().close();
    }
  }


  private ResponseBody executeGet(Request request)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException {
    Response response = this.transport.newCall(request).execute();

    if (response == null) {
      throw new NoResponseException();
    }

    ResponseBody body = response.body();

    if (response.isSuccessful()) {
      return body;
    }

    try {
      throw new ErrorResponseException(new ErrorResponse(body.charStream()));
    } finally {
      body.close();
    }
  }


  private ResponseBody executeGet(HttpUrl url)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException {
    Request request = getRequest(Method.GET, url, null);
    return executeGet(request);
  }


  private ResponseHeader executeHead(HttpUrl url)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException, MinioException {
    Request request = getRequest(Method.HEAD, url, null);
    Response response = this.transport.newCall(request).execute();

    if (response == null) {
      throw new NoResponseException();
    }

    ResponseHeader header = new ResponseHeader();
    HeaderParser.set(response.headers(), header);

    if (!response.isSuccessful()) {
      ErrorCode ec;
      String resource = url.encodedPath();
      String[] tokens = resource.split("/");
      String bucketName = null;
      String objectName = null;

      if (tokens.length > 1) {
        bucketName = tokens[1];
      }

      if (tokens.length > 2) {
        objectName = tokens[2];
      }

      switch (response.code()) {
        case 404:
          if (objectName != null) {
            ec = ErrorCode.NO_SUCH_KEY;
          } else if (bucketName != null) {
            ec = ErrorCode.NO_SUCH_BUCKET;
          } else {
            ec = ErrorCode.RESOURCE_NOT_FOUND;
          }
          break;
        case 501:
        case 405:
          ec = ErrorCode.METHOD_NOT_ALLOWED;
          break;
        case 409:
          if (bucketName != null) {
            ec = ErrorCode.NO_SUCH_BUCKET;
          } else {
            ec = ErrorCode.RESOURCE_CONFLICT;
          }
          break;
        case 403:
          ec = ErrorCode.ACCESS_DENIED;
          break;
        default:
          throw new MinioException("unhandled response code " + response.code()
                                     + ".  Please report this issue at https://github.com/minio/minio-java/issues");
      }

      throw new ErrorResponseException(new ErrorResponse(ec.code(), ec.message(), bucketName, objectName, resource,
                                                         header.getXamzRequestId(), header.getXamzId2()));
    }

    return header;
  }


  private void executeDelete(HttpUrl url)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException {
    Request request = getRequest(Method.DELETE, url, null);
    Response response = this.transport.newCall(request).execute();

    if (response == null) {
      throw new NoResponseException();
    }

    try {
      if (!response.isSuccessful()) {
        throw new ErrorResponseException(new ErrorResponse(response.body().charStream()));
      }
    } finally {
      response.body().close();
    }
  }


  private void executePost(HttpUrl url, byte[] data, XmlEntity entity)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException {
    Request request = getRequest(Method.POST, url, data);
    Response response = this.transport.newCall(request).execute();

    if (response == null) {
      throw new NoResponseException();
    }

    Reader bodyStream = response.body().charStream();

    try {
      if (!response.isSuccessful()) {
        throw new ErrorResponseException(new ErrorResponse(bodyStream));
      } else {
        if (entity != null) {
          entity.parseXml(bodyStream);
        }
      }
    } finally {
      response.body().close();
    }
  }


  private ResponseHeader executePut(Request request)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException {
    Response response = this.transport.newCall(request).execute();

    if (response == null) {
      throw new NoResponseException();
    }

    if (response.isSuccessful()) {
      ResponseHeader header = new ResponseHeader();
      HeaderParser.set(response.headers(), header);
      return header;
    }

    try {
      throw new ErrorResponseException(new ErrorResponse(response.body().charStream()));
    } finally {
      response.body().close();
    }
  }


  private HttpUrl getRequestUrl(String bucket, String key) throws InvalidBucketNameException,
                                                                  InvalidObjectNameException,
                                                                  UnsupportedEncodingException {
    if (bucket == null || "".equals(bucket.trim())) {
      throw new InvalidBucketNameException(bucket, "invalid bucket name");
    }
    if (key == null || "".equals(key.trim())) {
      throw new InvalidObjectNameException(key);
    }
    HttpUrl.Builder urlBuilder = this.url.newBuilder();
    urlBuilder.addPathSegment(bucket);
    // URLEncoder.encode replaces space with + and / with %2F
    for (String tok: URLEncoder.encode(key, "UTF-8").replace("+", "%20").replace("%2F", "/").split("/")) {
      urlBuilder.addEncodedPathSegment(tok);
    }
    HttpUrl url = urlBuilder.build();
    return url;
  }


  private HttpUrl getRequestUrl(String bucket) throws InvalidBucketNameException {
    if (bucket == null || "".equals(bucket.trim())) {
      throw new InvalidBucketNameException(bucket, "invalid bucket name");
    }

    HttpUrl url = this.url.newBuilder()
        .addPathSegment(bucket)
        .build();

    return url;
  }


  /**
   * Set application info to user agent - see http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
   *
   * @param name     your application name
   * @param version  your application version
   */
  @SuppressWarnings("unused")
  public void setAppInfo(String name, String version) {
    if (name == null || version == null) {
      // nothing to do
      return;
    }

    this.userAgent = DEFAULT_USER_AGENT + " " + name.trim() + "/" + version.trim();
  }


  /**
   * Returns metadata of given object.
   *
   * @param bucketName Bucket name.
   * @param objectName Object name in the bucket.
   *
   * @return Populated object metadata.
   *
   * @throws IOException     upon connection failure
   * @throws ClientException upon failure from server
   * @see ObjectStat
   */
  public ObjectStat statObject(String bucketName, String objectName)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    ResponseHeader header = executeHead(getRequestUrl(bucketName, objectName));
    return new ObjectStat(bucketName, objectName, header.getLastModified(), header.getContentLength(),
                          header.getEtag(), header.getContentType());
  }


  /**
   * Returns an InputStream containing the object. The InputStream must be closed when
   * complete or the connection will remain open.
   *
   * @param bucketName bucket name
   * @param objectName object name in the bucket
   *
   * @return an InputStream containing the object. Close the InputStream when done.
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public InputStream getObject(String bucketName, String objectName)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    ResponseBody body = executeGet(getRequestUrl(bucketName, objectName));
    return body.byteStream();
  }


  /** Returns an presigned URL containing the object.
   *
   * @param bucket  object's bucket
   * @param key     object's key
   * @param expires object expiration
   *
   * @throws IOException     upon signature calculation failure
   * @throws NoSuchAlgorithmException upon requested algorithm was not found during signature calculation
   * @throws InvalidExpiresRangeException upon input expires is out of range
   */
  public String presignedGetObject(String bucket, String key, Integer expires) throws IOException,
                                                                                      NoSuchAlgorithmException,
                                                                                      InvalidExpiresRangeException,
                                                                                      InvalidKeyException,
                                                                                      InvalidObjectNameException,
                                                                                      InternalClientException,
                                                                                      InvalidBucketNameException {
    if (expires < 1 || expires > DEFAULT_EXPIRY_TIME) {
      throw new InvalidExpiresRangeException();
    }
    HttpUrl url = getRequestUrl(bucket, key);
    Request request = getGetRequest(url);
    DateTime date = new DateTime();

    RequestSigner signer = new RequestSigner(null, this.accessKey,
                                             this.secretKey, date);
    return signer.preSignV4(request, expires);
  }


  /** Returns an presigned URL containing the object.
   *
   * @param bucket  object's bucket
   * @param key     object's key
   *
   * @throws IOException     upon connection error
   * @throws NoSuchAlgorithmException upon requested algorithm was not found during signature calculation
   * @throws InvalidExpiresRangeException upon input expires is out of range
   */
  public String presignedGetObject(String bucket, String key) throws IOException, NoSuchAlgorithmException,
                                                                     InvalidObjectNameException,
                                                                     InvalidExpiresRangeException,
                                                                     InvalidKeyException, InternalClientException,
                                                                     InvalidBucketNameException {
    return presignedGetObject(bucket, key, DEFAULT_EXPIRY_TIME);
  }


  /** Returns an presigned URL for PUT.
   *
   * @param bucket  object's bucket
   * @param key     object's key
   * @param expires object expiration
   *
   * @throws IOException     upon signature calculation failure
   * @throws NoSuchAlgorithmException upon requested algorithm was not found during signature calculation
   * @throws InvalidExpiresRangeException upon input expires is out of range
   */
  public String presignedPutObject(String bucket, String key, Integer expires) throws IOException,
                                                                                      NoSuchAlgorithmException,
                                                                                      InvalidExpiresRangeException,
                                                                                      InvalidKeyException,
                                                                                      InvalidObjectNameException,
                                                                                      InternalClientException,
                                                                                      InvalidBucketNameException {
    if (expires < 1 || expires > DEFAULT_EXPIRY_TIME) {
      throw new InvalidExpiresRangeException();
    }
    // place holder data to avoid okhttp's request builder's exception
    byte[] dummy = "".getBytes("UTF-8");
    HttpUrl url = getRequestUrl(bucket, key);
    Request request = getPutRequest(url, dummy);
    DateTime date = new DateTime();

    RequestSigner signer = new RequestSigner(null, this.accessKey,
                                             this.secretKey, date);
    return signer.preSignV4(request, expires);
  }


  /** Returns an presigned URL for PUT.
   *
   * @param bucket  object's bucket
   * @param key     object's key
   *
   * @throws IOException     upon connection error
   * @throws NoSuchAlgorithmException upon requested algorithm was not found during signature calculation
   * @throws InvalidExpiresRangeException upon input expires is out of range
   */
  public String presignedPutObject(String bucket, String key) throws IOException, NoSuchAlgorithmException,
                                                                     InvalidObjectNameException,
                                                                     InvalidExpiresRangeException, InvalidKeyException,
                                                                     InternalClientException,
                                                                     InvalidBucketNameException {
    return presignedPutObject(bucket, key, DEFAULT_EXPIRY_TIME);
  }


  /** Returns an Map for POST form data.
   *
   * @param policy new PostPolicy
   *
   * @throws NoSuchAlgorithmException upon requested algorithm was not found during signature calculation
   * @throws InvalidExpiresRangeException upon input expires is out of range
   * @throws UnsupportedEncodingException upon unsupported Encoding error
   */
  public Map<String, String> presignedPostPolicy(PostPolicy policy) throws UnsupportedEncodingException,
                                                                           NoSuchAlgorithmException,
                                                                           InvalidKeyException {
    DateTime date = new DateTime();
    RequestSigner signer = new RequestSigner(null, this.accessKey, this.secretKey, date);
    String region = Regions.INSTANCE.getRegion(this.url.uri().getHost());
    policy.setAlgorithm("AWS4-HMAC-SHA256");
    policy.setCredential(this.accessKey + "/" + signer.getScope(region, date));
    policy.setDate(date);

    String policybase64 = policy.base64();
    String signature = signer.postPreSignV4(policybase64, date, region);
    policy.setPolicy(policybase64);
    policy.setSignature(signature);
    return policy.getFormData();
  }


  /** Returns an InputStream containing a subset of the object. The InputStream must be
   *  closed or the connection will remain open.
   *
   * @param bucket      object's bucket
   * @param key         object's key
   * @param offsetStart Offset from the start of the object.
   *
   * @return an InputStream containing the object. Close the InputStream when done.
   *
   * @throws IOException     upon connection failure
   * @throws ClientException upon failure from server
   */
  public InputStream getPartialObject(String bucket, String key, long offsetStart)
    throws XmlPullParserException, IOException, MinioException {
    ObjectStat stat = statObject(bucket, key);
    long length = stat.getLength() - offsetStart;
    return getPartialObject(bucket, key, offsetStart, length);
  }


  /**
   * Returns an InputStream containing a subset of the object. The InputStream must be
   * closed or the connection will remain open.
   *
   * @param bucketName  Bucket name.
   * @param objectName  Object name in the bucket.
   * @param offsetStart Offset from the start of the object.
   * @param length      Length of bytes to retrieve.
   *
   * @return an InputStream containing the object. Close the InputStream when done.
   *
   * @throws IOException     upon connection failure
   * @throws ClientException upon failure from server
   */
  public InputStream getPartialObject(String bucketName, String objectName, long offsetStart, long length)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    if (offsetStart < 0 || length <= 0) {
      throw new InvalidRangeException();
    }

    HttpUrl url = getRequestUrl(bucketName, objectName);
    Request request = getGetRequest(url);
    long offsetEnd = offsetStart + length - 1;
    Request rangeRequest = request.newBuilder()
        .header("Range", "bytes=" + offsetStart + "-" + offsetEnd)
        .build();
    ResponseBody body = executeGet(rangeRequest);

    return body.byteStream();
  }


  /** Remove an object from a bucket.
   *
   * @param bucketName Bucket name
   * @param objectName Object name in the bucket
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public void removeObject(String bucketName, String objectName)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException,
           InvalidBucketNameException, InvalidObjectNameException {
    executeDelete(getRequestUrl(bucketName, objectName));
  }


  /**
   * listObjects is a wrapper around listObjects(bucket, null, true)
   *
   * @param bucket is the bucket to list objects from
   *
   * @return an iterator of Items.
   * @see #listObjects(String, String, boolean)
   */
  public Iterator<Result<Item>> listObjects(final String bucket) throws XmlPullParserException, MinioException {
    return listObjects(bucket, null);
  }


  /**
   * listObjects is a wrapper around listObjects(bucket, prefix, true)
   *
   * @param bucket to list objects of
   * @param prefix filters the list of objects to include only those that start with prefix
   *
   * @return an iterator of Items.
   * @see #listObjects(String, String, boolean)
   */
  public Iterator<Result<Item>> listObjects(final String bucket, final String prefix)
    throws XmlPullParserException, MinioException {
    // list all objects recursively
    return listObjects(bucket, prefix, true);
  }


  /**
   * @param bucket    bucket to list objects from
   * @param prefix    filters all objects returned where each object must begin with the given prefix
   * @param recursive when false, emulates a directory structure where each listing returned is either a full object
   *                  or part of the object's key up to the first '/'. All objects wit the same prefix up to the first
   *                  '/' will be merged into one entry.
   *
   * @return an iterator of Items.
   */
  public Iterator<Result<Item>> listObjects(final String bucket, final String prefix, final boolean recursive)
    throws XmlPullParserException {
    return new MinioIterator<Result<Item>>() {
      private String marker = null;
      private boolean isComplete = false;

      @Override
      protected List<Result<Item>> populate() throws XmlPullParserException {
        if (!isComplete) {
          String delimiter = null;
          // set delimiter  to '/' if not recursive to emulate directories
          if (!recursive) {
            delimiter = "/";
          }
          ListBucketResult listBucketResult;
          List<Result<Item>> items = new LinkedList<Result<Item>>();
          try {
            listBucketResult = listObjects(bucket, marker, prefix, delimiter, 1000);
            for (Item item : listBucketResult.getContents()) {
              items.add(new Result<Item>(item, null));
              if (listBucketResult.isTruncated()) {
                marker = item.getKey();
              }
            }
            for (Prefix prefix : listBucketResult.getCommonPrefixes()) {
              Item item = new Item();
              item.setKey(prefix.getPrefix());
              item.setIsDir(true);
              items.add(new Result<Item>(item, null));
            }
            if (listBucketResult.isTruncated() && delimiter != null) {
              marker = listBucketResult.getNextMarker();
            } else if (!listBucketResult.isTruncated()) {
              isComplete = true;
            }
          } catch (IOException e) {
            items.add(new Result<Item>(null, e));
            isComplete = true;
            return items;
          } catch (MinioException e) {
            items.add(new Result<Item>(null, e));
            isComplete = true;
            return items;
          }
          return items;
        }
        return new LinkedList<Result<Item>>();
      }
    };
  }


  private ListBucketResult listObjects(String bucket, String marker, String prefix, String delimiter, int maxKeys)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    HttpUrl url = getRequestUrl(bucket);

    // max keys limits the number of keys returned, max limit is 1000
    if (maxKeys >= 1000 || maxKeys < 0) {
      maxKeys = 1000;
    }
    url = url.newBuilder()
        .addQueryParameter("max-keys", Integer.toString(maxKeys))
        .addQueryParameter("marker", marker)
        .addQueryParameter("prefix", prefix)
        .addQueryParameter("delimiter", delimiter)
        .build();

    ListBucketResult result = new ListBucketResult();
    executeGet(url, result);
    return result;
  }


  /**
   * List buckets owned by the current user.
   *
   * @return a list of buckets owned by the current user
   *
   * @throws IOException     upon connection failure
   * @throws ClientException upon failure from server
   */
  public Iterator<Bucket> listBuckets()
    throws XmlPullParserException, IOException, NoResponseException, ErrorResponseException {
    ListAllMyBucketsResult retrievedBuckets = new ListAllMyBucketsResult();
    executeGet(this.url, retrievedBuckets);
    return retrievedBuckets.getBuckets().iterator();
  }


  /**
   * Test whether a bucket exists and the user has at least read access.
   *
   * @param bucketName Bucket name
   *
   * @return true if the bucket exists and the user has at least read access
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public boolean bucketExists(String bucketName)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    try {
      executeHead(getRequestUrl(bucketName));
      return true;
    } catch (ErrorResponseException e) {
      if (e.getErrorCode() != ErrorCode.NO_SUCH_BUCKET) {
        throw e;
      }
    }
    return false;
  }


  /**
   * @param bucket bucket to create.
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public void makeBucket(String bucket) throws XmlPullParserException, IOException, MinioException {
    this.makeBucket(bucket, Acl.PRIVATE);
  }


  /**
   * Create a bucket with a given name and ACL.
   *
   * @param bucketName Bucket name
   * @param acl        Canned ACL
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public void makeBucket(String bucketName, Acl acl)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException,
           InvalidBucketNameException, InvalidObjectNameException, InvalidAclNameException {
    if (acl == null) {
      acl = Acl.PRIVATE;
    }

    HttpUrl url = getRequestUrl(bucketName);
    Request request = null;

    CreateBucketConfiguration config = new CreateBucketConfiguration();
    String region = Regions.INSTANCE.getRegion(url.uri().getHost());

    // ``us-east-1`` is not a valid location constraint according to amazon, so we skip it
    // Valid constraints are
    // [ us-west-1 | us-west-2 | EU or eu-west-1 | eu-central-1 | ap-southeast-1 | ap-northeast-1 |
    // ap-southeast-2 | sa-east-1 ]
    if (!"us-east-1".equals(region)) {
      config.setLocationConstraint(region);
      byte[] data = config.toString().getBytes("UTF-8");
      byte[] md5sum = calculateMd5sum(data);
      String base64md5sum = "";
      if (md5sum != null) {
        base64md5sum = BaseEncoding.base64().encode(md5sum);
      }
      request = getPutRequest(url, data);
      request = request.newBuilder()
          .header("Content-MD5", base64md5sum)
          .build();
    } else {
      request = getPutRequest(url, "".getBytes("UTF-8"));
    }

    request = request.newBuilder()
        .header("x-amz-acl", acl.toString())
        .build();

    executePut(request);
  }


  /**
   * Remove a bucket with a given name.
   * <p>
   * NOTE: -
   * All objects (including all object versions and delete markers) in the bucket
   * must be deleted prior, this API will not recursively delete objects
   * </p>
   *
   * @param bucketName Bucket name
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public void removeBucket(String bucketName)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException,
           InvalidBucketNameException {
    executeDelete(getRequestUrl(bucketName));
  }


  /**
   * Get the bucket's ACL.
   *
   * @param bucket bucket to get ACL on
   *
   * @return Acl type
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public Acl getBucketAcl(String bucket) throws XmlPullParserException, IOException, MinioException {
    AccessControlPolicy policy = this.getAccessPolicy(bucket);
    if (policy == null) {
      throw new InvalidArgumentException();
    }
    Acl acl = Acl.PRIVATE;
    List<Grant> accessControlList = policy.getAccessControlList();
    switch (accessControlList.size()) {
      case 1:
        for (Grant grant : accessControlList) {
          if (grant.getGrantee().getUri() == null && "FULL_CONTROL".equals(grant.getPermission())) {
            acl = Acl.PRIVATE;
            break;
          }
        }
        break;
      case 2:
        for (Grant grant : accessControlList) {
          if ("http://acs.amazonaws.com/groups/global/AuthenticatedUsers".equals(grant.getGrantee().getUri())
              &&
              "READ".equals(grant.getPermission())) {
            acl = Acl.AUTHENTICATED_READ;
            break;
          }
          if ("http://acs.amazonaws.com/groups/global/AllUsers".equals(grant.getGrantee().getUri())
              &&
              "READ".equals(grant.getPermission())) {
            acl = Acl.PUBLIC_READ;
            break;
          }
        }
        break;
      case 3:
        for (Grant grant : accessControlList) {
          if ("http://acs.amazonaws.com/groups/global/AllUsers".equals(grant.getGrantee().getUri())
              &&
              "WRITE".equals(grant.getPermission())) {
            acl = Acl.PUBLIC_READ_WRITE;
            break;
          }
        }
        break;
      default:
        throw new InternalClientException("Invalid control flow.  Please report this issue at "
                                          + "https://github.com/minio/minio-java/issues");
    }
    return acl;
  }


  private AccessControlPolicy getAccessPolicy(String bucket)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    HttpUrl url = getRequestUrl(bucket);
    url = url.newBuilder()
        .addQueryParameter("acl", "")
        .build();

    AccessControlPolicy policy = new AccessControlPolicy();
    executeGet(url, policy);
    return policy;
  }


  /**
   * Set the bucket's ACL.
   *
   * @param bucketName Bucket name
   * @param acl        Canned ACL
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public void setBucketAcl(String bucketName, Acl acl)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException,
           InvalidBucketNameException, InvalidObjectNameException, InvalidAclNameException {
    if (acl == null) {
      throw new InvalidAclNameException();
    }

    HttpUrl url = getRequestUrl(bucketName);
    url = url.newBuilder()
        .addQueryParameter("acl", "")
        .build();

    Request request = getPutRequest(url, "".getBytes("UTF-8"));
    request = request.newBuilder()
        .header("x-amz-acl", acl.toString())
        .build();

    executePut(request);
  }


  /**
   * Create an object.
   * <p>
   * If the object is larger than 5MB, the client will automatically use a multipart session.
   * </p>
   * <p>
   * If the session fails, the user may attempt to re-upload the object by attempting to create
   * the exact same object again. The client will examine all parts of any current upload session
   * and attempt to reuse the session automatically. If a mismatch is discovered, the upload will fail
   * before uploading any more data. Otherwise, it will resume uploading where the session left off.
   * </p>
   * <p>
   * If the multipart session fails, the user is responsible for resuming or removing the session.
   * </p>
   *
   * @param bucket      Bucket to use
   * @param key         Key of object
   * @param contentType Content type to set this object to
   * @param size        Size of all the data that will be uploaded.
   * @param body        Data to upload
   *
   * @throws IOException     upon connection error
   * @throws ClientException upon failure from server
   */
  public void putObject(String bucket, String key, String contentType, long size, InputStream body)
    throws XmlPullParserException, IOException, MinioException {
    boolean isMultipart = false;
    boolean newUpload = true;
    int partSize = 0;
    String uploadId = null;

    if (contentType == null || "".equals(contentType.trim())) {
      contentType = "application/octet-stream";
    }

    if (size > MIN_MULTIPART_SIZE) {
      // check if multipart exists
      Iterator<Result<Upload>> multipartUploads = listIncompleteUploads(bucket, key);
      while (multipartUploads.hasNext()) {
        Upload upload = multipartUploads.next().getResult();
        if (upload.getKey().equals(key)) {
          uploadId = upload.getUploadId();
          newUpload = false;
        }
      }

      isMultipart = true;
      partSize = calculatePartSize(size);
    }

    if (!isMultipart) {
      Data data = readData((int) size, body);
      if (data.getData().length != size || destructiveHasMore(body)) {
        throw new UnexpectedShortReadException();
      }
      try {
        putObject(bucket, key, contentType, data.getData(), data.getMD5());
      } catch (MethodNotAllowedException ex) {
        throw new ObjectAlreadyExistsException(key, bucket);
      }
      return;
    }
    long totalSeen = 0;
    List<Part> parts = new LinkedList<Part>();
    int partNumber = 1;
    Iterator<Part> existingParts = new LinkedList<Part>().iterator();
    if (newUpload) {
      uploadId = newMultipartUpload(bucket, key);
    } else {
      existingParts = listObjectParts(bucket, key, uploadId);
    }
    while (true) {
      Data data = readData(partSize, body);
      if (data.getData().length == 0) {
        break;
      }
      if (data.getData().length < partSize) {
        long expectedSize = size - totalSeen;
        if (expectedSize != data.getData().length) {
          throw new UnexpectedShortReadException();
        }
      }
      if (!newUpload && existingParts.hasNext()) {
        Part existingPart = existingParts.next();
        if (existingPart.getPartNumber() == partNumber
            &&
            existingPart.getETag().toLowerCase().equals(BaseEncoding.base16().encode(data.getMD5()).toLowerCase())) {
          partNumber++;
          continue;
        }
      }
      String etag = putObject(bucket, key, contentType, data.getData(),
                              data.getMD5(), uploadId, partNumber);
      totalSeen += data.getData().length;

      Part part = new Part();
      part.setPartNumber(partNumber);
      part.setETag(etag);
      parts.add(part);
      partNumber++;
    }
    if (totalSeen != size) {
      throw new InputSizeMismatchException();
    }

    completeMultipart(bucket, key, uploadId, parts);
  }


  private void putObject(String bucket, String key, String contentType, byte[] data, byte[] md5sum)
    throws XmlPullParserException, IOException, MinioException {
    putObject(bucket, key, contentType, data, md5sum, "", 0);
  }


  private String putObject(String bucketName, String objectName, String contentType, byte[] data, byte[] md5sum,
                           String uploadId, int partId)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException,
           InvalidBucketNameException, InvalidObjectNameException {
    HttpUrl url = getRequestUrl(bucketName, objectName);

    if (partId > 0 && uploadId != null && !"".equals(uploadId.trim())) {
      url = url.newBuilder()
          .addQueryParameter("partNumber", Integer.toString(partId))
          .addQueryParameter("uploadId", uploadId)
          .build();
    }

    Request request = getPutRequest(url, data);

    if (md5sum != null) {
      request = request.newBuilder()
          .header("Content-MD5", BaseEncoding.base64().encode(md5sum))
          .build();
    }

    ResponseHeader header = executePut(request);
    return header.getEtag();
  }


  /**
   * listIncompleteUploads is a wrapper around listIncompleteUploads(bucket, null, true)
   *
   * @param bucket is the bucket to list objects from
   *
   * @return an iterator of Upload.
   * @see #listIncompleteUploads(String, String, boolean)
   */
  public Iterator<Result<Upload>> listIncompleteUploads(String bucket) throws XmlPullParserException {
    return listIncompleteUploads(bucket, null, true);
  }


  /**
   * listIncompleteUploads is a wrapper around listIncompleteUploads(bucket, prefix, true)
   *
   * @param bucket is the bucket to list incomplete uploads from
   * @param prefix filters the list of uploads to include only those that start with prefix
   *
   * @return an iterator of Upload.
   * @see #listIncompleteUploads(String, String, boolean)
   */
  public Iterator<Result<Upload>> listIncompleteUploads(String bucket, String prefix) throws XmlPullParserException {
    return listIncompleteUploads(bucket, prefix, true);
  }


  /**
   * @param bucket    bucket to list incomplete uploads from
   * @param prefix    filters all uploads returned where each object must begin with the given prefix
   * @param recursive when false, emulates a directory structure where each listing returned is either a full object
   *                  or part of the object's key up to the first '/'. All uploads with the same prefix up to the first
   *                  '/' will be merged into one entry.
   *
   * @return an iterator of Upload.
   */
  public Iterator<Result<Upload>> listIncompleteUploads(final String bucket, final String prefix,
                                                        final boolean recursive) throws XmlPullParserException {

    return new MinioIterator<Result<Upload>>() {
      private boolean isComplete = false;
      private String keyMarker = null;
      private String uploadIdMarker;

      @Override
      protected List<Result<Upload>> populate() throws XmlPullParserException {
        List<Result<Upload>> ret = new LinkedList<Result<Upload>>();
        if (!isComplete) {
          ListMultipartUploadsResult uploadResult;
          String delimiter = null;
          // set delimiter  to '/' if not recursive to emulate directories
          if (!recursive) {
            delimiter = "/";
          }
          try {
            uploadResult = listIncompleteUploads(bucket, keyMarker,
                                                 uploadIdMarker, prefix,
                                                 delimiter, 1000);
            if (uploadResult.isTruncated()) {
              keyMarker = uploadResult.getNextKeyMarker();
              uploadIdMarker = uploadResult.getNextUploadIdMarker();
            } else {
              isComplete = true;
            }
            List<Upload> uploads = uploadResult.getUploads();
            for (Upload upload : uploads) {
              ret.add(new Result<Upload>(upload, null));
            }
          } catch (IOException e) {
            ret.add(new Result<Upload>(null, e));
            isComplete = true;
          } catch (MinioException e) {
            ret.add(new Result<Upload>(null, e));
            isComplete = true;
          }
        }
        return ret;
      }
    };
  }


  private ListMultipartUploadsResult listIncompleteUploads(String bucket, String keyMarker, String uploadIdMarker,
                                                           String prefix, String delimiter, int maxUploads)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    HttpUrl url = getRequestUrl(bucket);
    // max uploads limits the number of uploads returned, max limit is 1000
    if (maxUploads >= 1000 || maxUploads < 0) {
      maxUploads = 1000;
    }

    url = url.newBuilder()
        .addQueryParameter("uploads", "")
        .addQueryParameter("max-uploads", Integer.toString(maxUploads))
        .addQueryParameter("prefix", prefix)
        .addQueryParameter("key-marker", keyMarker)
        .addQueryParameter("upload-id-marker", uploadIdMarker)
        .addQueryParameter("delimiter", delimiter)
        .build();

    ListMultipartUploadsResult result = new ListMultipartUploadsResult();
    executeGet(url, result);
    return result;
  }


  private String newMultipartUpload(String bucketName, String objectName)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException,
           InvalidBucketNameException, InvalidObjectNameException {
    InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
    HttpUrl url = getRequestUrl(bucketName, objectName);
    url = url.newBuilder()
        .addQueryParameter("uploads", "")
        .build();

    executePost(url, "".getBytes("UTF-8"), result);
    return result.getUploadId();
  }


  private void completeMultipart(String bucketName, String objectName, String uploadId, List<Part> parts)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException,
           InvalidBucketNameException, InvalidObjectNameException {
    HttpUrl url = getRequestUrl(bucketName, objectName);
    url = url.newBuilder()
        .addQueryParameter("uploadId", uploadId)
        .build();

    CompleteMultipartUpload completeManifest = new CompleteMultipartUpload();
    completeManifest.setParts(parts);

    // TODO: handle response xml properly than null
    executePost(url, completeManifest.toString().getBytes("UTF-8"), null);
  }


  private Iterator<Part> listObjectParts(final String bucket, final String key,
                                         final String uploadId) throws XmlPullParserException {
    return new MinioIterator<Part>() {
      public int marker;
      private boolean isComplete = false;

      @Override
      protected List<Part> populate() throws XmlPullParserException, IOException, MinioException {
        if (!isComplete) {
          ListPartsResult result;
          result = listObjectParts(bucket, key, uploadId, marker);
          if (result.isTruncated()) {
            marker = result.getNextPartNumberMarker();
          } else {
            isComplete = true;
          }
          return result.getParts();
        }
        return new LinkedList<Part>();
      }
    };
  }


  private ListPartsResult listObjectParts(String bucket, String key, String uploadId, int partNumberMarker)
    throws XmlPullParserException, IOException, MinioException, NoResponseException, ErrorResponseException {
    if (partNumberMarker <= 0) {
      throw new InvalidArgumentException();
    }

    HttpUrl url = getRequestUrl(bucket, key);
    url = url.newBuilder()
        .addQueryParameter("uploadId", uploadId)
        .addQueryParameter("part-number-marker",
                           Integer.toString(partNumberMarker))
        .build();

    ListPartsResult result = new ListPartsResult();
    executeGet(url, result);
    return result;
  }


  private void abortMultipartUpload(String bucketName, String objectName, String uploadId)
    throws IOException, XmlPullParserException, NoResponseException, ErrorResponseException, MinioException,
           InvalidBucketNameException {
    if (bucketName == null || objectName == null || uploadId == null) {
      throw new MinioException("Illegal argument");
    }

    HttpUrl url = getRequestUrl(bucketName, objectName);
    url = url.newBuilder()
        .addQueryParameter("uploadId", uploadId)
        .build();

    executeDelete(url);
  }


  /**
   * Remove active multipart uploads, starting from key.
   *
   * @param bucket of multipart upload to remove
   * @param key    of multipart upload to remove
   *
   * @throws IOException     upon connection failure
   * @throws ClientException upon failure from server
   */
  public void removeIncompleteUpload(String bucket, String key)
    throws XmlPullParserException, IOException, MinioException {
    Iterator<Result<Upload>> uploads = listIncompleteUploads(bucket, key);
    while (uploads.hasNext()) {
      Upload upload = uploads.next().getResult();
      if (key.equals(upload.getKey())) {
        abortMultipartUpload(bucket, key, upload.getUploadId());
        return;
      }
    }
  }


  private int calculatePartSize(long size) {
    // 9999 is used instead of 10000 to cater for the last part being too small
    int partSize = (int) (size / 9999);
    if (partSize > MIN_MULTIPART_SIZE) {
      if (partSize > MAX_MULTIPART_SIZE) {
        return MAX_MULTIPART_SIZE;
      }
      return partSize;
    }
    return MIN_MULTIPART_SIZE;
  }


  private byte[] calculateMd5sum(byte[] data) {
    byte[] md5sum;
    try {
      MessageDigest md5Digest = MessageDigest.getInstance("MD5");
      md5sum = md5Digest.digest(data);
    } catch (NoSuchAlgorithmException e) {
      // we should never see this, unless the underlying JVM is broken.
      // Throw a runtime exception if we run into this, the environment
      // is not sane
      System.err.println("MD5 message digest type not found, the current JVM is likely broken.");
      throw new RuntimeException(e);
    }
    return md5sum;
  }


  private Data readData(int size, InputStream data) throws IOException {
    int amountRead = 0;
    byte[] fullData = new byte[size];
    while (amountRead != size) {
      byte[] buf = new byte[size - amountRead];
      int curRead = data.read(buf);
      if (curRead == -1) {
        break;
      }
      buf = Arrays.copyOf(buf, curRead);
      System.arraycopy(buf, 0, fullData, amountRead, curRead);
      amountRead += curRead;
    }
    fullData = Arrays.copyOfRange(fullData, 0, amountRead);
    Data d = new Data();
    d.setData(fullData);
    d.setMD5(calculateMd5sum(fullData));
    return d;
  }


  private boolean destructiveHasMore(InputStream data) {
    try {
      return data.read() > -1;
    } catch (IOException e) {
      return false;
    }
  }


  /**
   * Enable logging to a java logger for debugging purposes. This will enable logging for all http requests.
   */
  @SuppressWarnings("unused")
  public void enableLogging() {
    if (this.logger.get() == null) {
      this.logger.set(Logger.getLogger(OkHttpClient.class.getName()));
      this.logger.get().setLevel(Level.CONFIG);
      this.logger.get().addHandler(new Handler() {

        @Override
        public void close() throws SecurityException {
        }

        @Override
        public void flush() {
        }

        @Override
        public void publish(LogRecord record) {
          // default ConsoleHandler will print >= INFO to System.err
          if (record.getLevel().intValue() < Level.INFO.intValue()) {
            System.out.println(record.getMessage());
          }
        }
      });
    } else {
      this.logger.get().setLevel(Level.CONFIG);
    }
  }


  /**
   * Disable logging http requests.
   */
  @SuppressWarnings("unused")
  public void disableLogging() {
    if (this.logger.get() != null) {
      this.logger.get().setLevel(Level.OFF);
    }
  }


  public URL getUrl() {
    return this.url.url();
  }
}