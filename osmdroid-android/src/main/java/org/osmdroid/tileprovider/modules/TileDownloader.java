package org.osmdroid.tileprovider.modules;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import org.osmdroid.api.IMapView;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.constants.OpenStreetMapTileProviderConstants;
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.util.Counters;
import org.osmdroid.tileprovider.util.StreamUtils;
import org.osmdroid.util.MapTileIndex;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Map;

/**
 * @since 6.0.2
 * @author Fabrice Fontaine
 */
public class TileDownloader {

    public Drawable downloadTile(final long pMapTileIndex,
                                 final IFilesystemCache pFilesystemCache, final OnlineTileSourceBase pTileSource) throws CantContinueException {
        return downloadTile(pMapTileIndex, 0, pTileSource.getTileURLString(pMapTileIndex), pFilesystemCache, pTileSource);
    }

    /**
     * downloads a tile and follows http redirects
     * Code used to be in MapTileDownloader.TileLoader.downloadTile
     */
    public Drawable downloadTile(final long pMapTileIndex, final int redirectCount, final String targetUrl,
                                 final IFilesystemCache pFilesystemCache, final OnlineTileSourceBase pTileSource) throws CantContinueException {

        //prevent infinite looping of redirects, rare but very possible for misconfigured servers
        if (redirectCount>3) {
            return null;
        }

        InputStream in = null;
        OutputStream out = null;
        HttpURLConnection c=null;
        ByteArrayInputStream byteStream = null;
        ByteArrayOutputStream dataStream = null;
        try {
            final String tileURLString = targetUrl;

            if (Configuration.getInstance().isDebugMode()) {
                Log.d(IMapView.LOGTAG,"Downloading Maptile from url: " + tileURLString);
            }

            if (TextUtils.isEmpty(tileURLString)) {
                return null;
            }

            //TODO in the future, it may be necessary to allow app's using this library to override the SSL socket factory. It would here somewhere
            if (Configuration.getInstance().getHttpProxy() != null) {
                c = (HttpURLConnection) new URL(tileURLString).openConnection(Configuration.getInstance().getHttpProxy());
            } else {
                c = (HttpURLConnection) new URL(tileURLString).openConnection();
            }
            c.setUseCaches(true);
            c.setRequestProperty(Configuration.getInstance().getUserAgentHttpHeader(),Configuration.getInstance().getUserAgentValue());
            for (final Map.Entry<String, String> entry : Configuration.getInstance().getAdditionalHttpRequestProperties().entrySet()) {
                c.setRequestProperty(entry.getKey(), entry.getValue());
            }
            c.connect();


            // Check to see if we got success

            if (c.getResponseCode() != 200) {


                switch (c.getResponseCode()) {
                    case 301:
                    case 302:
                    case 307:
                    case 308:
                        if (Configuration.getInstance().isMapTileDownloaderFollowRedirects()) {
                            //this is a redirect, check the header for a 'Location' header
                            String redirectUrl = c.getHeaderField("Location");
                            if (redirectUrl != null) {
                                if (redirectUrl.startsWith("/")) {
                                    //in this case we need to stitch together a full url
                                    URL old = new URL(targetUrl);
                                    int port = old.getPort();
                                    boolean secure = targetUrl.toLowerCase().startsWith("https://");
                                    if (port == -1)
                                        if (targetUrl.toLowerCase().startsWith("http://")) {
                                            port = 80;
                                        } else {
                                            port = 443;
                                        }

                                    redirectUrl = (secure ? "https://" : "http") + old.getHost() + ":" + port + redirectUrl;
                                }
                                Log.i(IMapView.LOGTAG, "Http redirect for MapTile: " + MapTileIndex.toString(pMapTileIndex) + " HTTP response: " + c.getResponseMessage() + " to url " + redirectUrl);
                                return downloadTile(pMapTileIndex, redirectCount + 1, redirectUrl, pFilesystemCache, pTileSource);
                            }
                            break;
                        }	//else follow through the normal path of aborting the download
                    default: {
                        Log.w(IMapView.LOGTAG, "Problem downloading MapTile: " + MapTileIndex.toString(pMapTileIndex) + " HTTP response: " + c.getResponseMessage());
                        if (Configuration.getInstance().isDebugMapTileDownloader()) {
                            Log.d(IMapView.LOGTAG, tileURLString);
                        }
                        Counters.tileDownloadErrors++;
                        in = c.getErrorStream(); // in order to have the error stream purged by the finally block
                        return null;
                    }
                }



            }
            String mime = c.getHeaderField("Content-Type");
            if (Configuration.getInstance().isDebugMapTileDownloader()) {
                Log.d(IMapView.LOGTAG, tileURLString + " success, mime is " + mime );
            }
            if (mime!=null && !mime.toLowerCase().contains("image")) {
                Log.w(IMapView.LOGTAG, tileURLString + " success, however the mime type does not appear to be an image " + mime );
            }

            in = c.getInputStream();

            dataStream = new ByteArrayOutputStream();
            out = new BufferedOutputStream(dataStream, StreamUtils.IO_BUFFER_SIZE);
            final long expirationTime = computeExpirationTime(
                    c.getHeaderField(OpenStreetMapTileProviderConstants.HTTP_EXPIRES_HEADER));
            StreamUtils.copy(in, out);
            out.flush();
            final byte[] data = dataStream.toByteArray();
            byteStream = new ByteArrayInputStream(data);

            // Save the data to the cache
            //this is the only point in which we insert tiles to the db or local file system.

            if (pFilesystemCache != null) {
                pFilesystemCache.saveFile(pTileSource, pMapTileIndex, byteStream, expirationTime);
                byteStream.reset();
            }
            return pTileSource.getDrawable(byteStream);
        } catch (final UnknownHostException e) {
            Log.w(IMapView.LOGTAG,"UnknownHostException downloading MapTile: " + MapTileIndex.toString(pMapTileIndex) + " : " + e);
            Counters.tileDownloadErrors++;
        } catch (final BitmapTileSourceBase.LowMemoryException e) {
            // low memory so empty the queue
            Counters.countOOM++;
            Log.w(IMapView.LOGTAG,"LowMemoryException downloading MapTile: " + MapTileIndex.toString(pMapTileIndex) + " : " + e);
            throw new CantContinueException(e);
        } catch (final FileNotFoundException e) {
            Counters.tileDownloadErrors++;
            Log.w(IMapView.LOGTAG,"Tile not found: " + MapTileIndex.toString(pMapTileIndex) + " : " + e);
        } catch (final IOException e) {
            Counters.tileDownloadErrors++;
            Log.w(IMapView.LOGTAG,"IOException downloading MapTile: " + MapTileIndex.toString(pMapTileIndex) + " : " + e);
        } catch (final Throwable e) {
            Counters.tileDownloadErrors++;
            Log.e(IMapView.LOGTAG,"Error downloading MapTile: " + MapTileIndex.toString(pMapTileIndex), e);
        } finally {
            StreamUtils.closeStream(in);
            StreamUtils.closeStream(out);
            StreamUtils.closeStream(byteStream);
            StreamUtils.closeStream(dataStream);
            try{
                c.disconnect();
            } catch (Exception ex){}
        }

        return null;
    }

    private long computeExpirationTime(final String pHttpExpiresHeader) {
        Long httpExpirationTime = null;
        if (pHttpExpiresHeader != null && pHttpExpiresHeader.length() > 0) {
            try {
                final Date dateExpires = Configuration.getInstance().getHttpHeaderDateTimeFormat().parse(pHttpExpiresHeader);
                httpExpirationTime = dateExpires.getTime();
            } catch (final Exception ex) {
                if (Configuration.getInstance().isDebugMapTileDownloader())
                    Log.d(IMapView.LOGTAG, "Unable to parse expiration tag for tile, using default, server returned " + pHttpExpiresHeader, ex);
            }
        }
        return computeExpirationTime(httpExpirationTime);
    }

    protected long computeExpirationTime(final Long pHttpExpiresTime) {
        final Long override=Configuration.getInstance().getExpirationOverrideDuration();
        final long now = System.currentTimeMillis();
        if (override != null) {
            return now + override;
        }
        final long extension = Configuration.getInstance().getExpirationExtendedDuration();
        if (pHttpExpiresTime != null) {
            return pHttpExpiresTime + extension;
        }
        return now + OpenStreetMapTileProviderConstants.DEFAULT_MAXIMUM_CACHED_FILE_AGE + extension;
    }
}
