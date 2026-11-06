package com.runwise.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.runwise.app.ui.theme.CardDark
import com.runwise.app.ui.theme.RunwiseOrange
import com.runwise.app.ui.theme.TextMutedDark
import com.runwise.app.ui.theme.TextPrimaryDark

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun LeafletMapView(
    coordinates: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    currentLocation: Pair<Double, Double>? = null,
    scrubCoordinate: Pair<Double, Double>? = null,
    isLiveTracking: Boolean = false,
    emptyMessage: String = "No GPS route recorded"
) {
    if (coordinates.isEmpty() && currentLocation == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .background(CardDark),
            contentAlignment = Alignment.Center
        ) {
            Text(emptyMessage, color = TextMutedDark, fontSize = 12.sp)
        }
        return
    }

    val context = LocalContext.current
    var showVectorOnly by remember { mutableStateOf(true) }

    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val htmlContent = remember(coordinates, currentLocation) {
        buildLeafletHtml(coordinates, currentLocation, isLiveTracking)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121214))
    ) {
        // Native Compose Canvas polyline rendering (always present as base / offline fallback)
        if (coordinates.size >= 2) {
            NativeRouteCanvas(
                coordinates = coordinates,
                scrubCoordinate = scrubCoordinate,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Leaflet WebView with AndroidX WebViewAssetLoader
        if (!showVectorOnly) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.allowFileAccess = false // Secure: served via WebViewAssetLoader
                        settings.allowContentAccess = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                android.util.Log.d(
                                    "LeafletMapView",
                                    "JS: [${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})"
                                )
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                android.util.Log.w(
                                    "LeafletMapView",
                                    "HTTP error loading ${request?.url}: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase}"
                                )
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                android.util.Log.w(
                                    "LeafletMapView",
                                    "WebView error loading ${request?.url}: ${error?.description}"
                                )
                            }
                        }

                        setOnTouchListener { v, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }

                        // Load with HTTPS origin so Carto tile requests are standard HTTPS-to-HTTPS requests
                        loadDataWithBaseURL(
                            "https://appassets.androidplatform.net/assets/leaflet/",
                            htmlContent,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { webView ->
                    if (isLiveTracking && currentLocation != null) {
                        webView.evaluateJavascript(
                            "if (typeof appendLivePoint === 'function') { appendLivePoint(${currentLocation.first}, ${currentLocation.second}); }",
                            null
                        )
                    }
                    if (scrubCoordinate != null) {
                        webView.evaluateJavascript(
                            "if (typeof setScrubMarker === 'function') { setScrubMarker(${scrubCoordinate.first}, ${scrubCoordinate.second}); }",
                            null
                        )
                    } else {
                        webView.evaluateJavascript(
                            "if (typeof setScrubMarker === 'function') { setScrubMarker(null, null); }",
                            null
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // View toggle pill in top-right corner
        if (coordinates.size >= 2) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC1E1E22))
                    .clickable { showVectorOnly = !showVectorOnly }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (showVectorOnly) "ROUTE" else "TILES",
                    color = if (showVectorOnly) RunwiseOrange else TextPrimaryDark,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Attribution in bottom-right corner (shown when street tiles active)
        if (!showVectorOnly) {
            Text(
                text = "© CartoDB, © OpenStreetMap",
                color = Color(0x66FFFFFF),
                fontSize = 8.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
        }
    }
}

/**
 * High-performance native Compose Canvas route polyline renderer.
 * Converts lat/lng into local equirectangular projected coordinates:
 *   x = R * cos(phi0) * (lambda - lambda0)
 *   y = R * (phi - phi0)
 * Preserves aspect ratio and scales to Canvas with start/finish markers and synced scrub marker.
 */
@Composable
fun NativeRouteCanvas(
    coordinates: List<Pair<Double, Double>>,
    scrubCoordinate: Pair<Double, Double>? = null,
    modifier: Modifier = Modifier
) {
    if (coordinates.size < 2) return

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padding = 28.dp.toPx()

        // Mean Earth radius in meters
        val r = 6371000.0

        val lats = coordinates.map { it.first }
        val lngs = coordinates.map { it.second }
        val minLat = lats.minOrNull() ?: 0.0
        val maxLat = lats.maxOrNull() ?: 0.0
        val minLng = lngs.minOrNull() ?: 0.0
        val maxLng = lngs.maxOrNull() ?: 0.0

        val phi0 = Math.toRadians((minLat + maxLat) / 2.0)
        val lambda0 = Math.toRadians((minLng + maxLng) / 2.0)
        val cosPhi0 = kotlin.math.cos(phi0)

        // Equirectangular local projection
        val projected = coordinates.map { pt ->
            val phi = Math.toRadians(pt.first)
            val lambda = Math.toRadians(pt.second)
            val x = r * cosPhi0 * (lambda - lambda0)
            val y = r * (phi - phi0)
            Pair(x, y)
        }

        val xs = projected.map { it.first }
        val ys = projected.map { it.second }
        val minX = xs.minOrNull() ?: 0.0
        val maxX = xs.maxOrNull() ?: 0.0
        val minY = ys.minOrNull() ?: 0.0
        val maxY = ys.maxOrNull() ?: 0.0

        val deltaX = (maxX - minX).coerceAtLeast(1.0)
        val deltaY = (maxY - minY).coerceAtLeast(1.0)

        val usableW = (w - 2 * padding).coerceAtLeast(10f)
        val usableH = (h - 2 * padding).coerceAtLeast(10f)

        val scale = minOf(usableW / deltaX, usableH / deltaY)
        val offsetX = padding + (usableW - deltaX * scale) / 2f
        val offsetY = padding + (usableH - deltaY * scale) / 2f

        // Draw subtle background grid
        val gridStep = 45.dp.toPx()
        var gx = 0f
        while (gx < w) {
            drawLine(color = Color(0x12FFFFFF), start = Offset(gx, 0f), end = Offset(gx, h), strokeWidth = 1f)
            gx += gridStep
        }
        var gy = 0f
        while (gy < h) {
            drawLine(color = Color(0x12FFFFFF), start = Offset(0f, gy), end = Offset(w, gy), strokeWidth = 1f)
            gy += gridStep
        }

        // Draw polyline
        val path = Path()
        projected.forEachIndexed { i, pt ->
            val cx = (offsetX + (pt.first - minX) * scale).toFloat()
            val cy = (h - (offsetY + (pt.second - minY) * scale)).toFloat()
            if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
        }

        drawPath(
            path = path,
            color = Color(0xFFFC5200),
            style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Draw green start pin
        val startPt = projected.first()
        val startX = (offsetX + (startPt.first - minX) * scale).toFloat()
        val startY = (h - (offsetY + (startPt.second - minY) * scale)).toFloat()
        drawCircle(color = Color(0xFF00E676), radius = 6.5.dp.toPx(), center = Offset(startX, startY))
        drawCircle(color = Color.White, radius = 6.5.dp.toPx(), center = Offset(startX, startY), style = Stroke(width = 2.dp.toPx()))

        // Draw red finish pin
        val endPt = projected.last()
        val endX = (offsetX + (endPt.first - minX) * scale).toFloat()
        val endY = (h - (offsetY + (endPt.second - minY) * scale)).toFloat()
        drawCircle(color = Color(0xFFFF3B30), radius = 6.5.dp.toPx(), center = Offset(endX, endY))
        drawCircle(color = Color.White, radius = 6.5.dp.toPx(), center = Offset(endX, endY), style = Stroke(width = 2.dp.toPx()))

        // Draw interactive scrub marker if active
        if (scrubCoordinate != null) {
            val scrubPhi = Math.toRadians(scrubCoordinate.first)
            val scrubLambda = Math.toRadians(scrubCoordinate.second)
            val scrubX = r * cosPhi0 * (scrubLambda - lambda0)
            val scrubY = r * (scrubPhi - phi0)
            val scrubCanvasX = (offsetX + (scrubX - minX) * scale).toFloat()
            val scrubCanvasY = (h - (offsetY + (scrubY - minY) * scale)).toFloat()

            drawCircle(color = Color(0x66FC5200), radius = 12.dp.toPx(), center = Offset(scrubCanvasX, scrubCanvasY))
            drawCircle(color = Color(0xFFFC5200), radius = 6.5.dp.toPx(), center = Offset(scrubCanvasX, scrubCanvasY))
            drawCircle(color = Color.White, radius = 6.5.dp.toPx(), center = Offset(scrubCanvasX, scrubCanvasY), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private fun buildLeafletHtml(
    coordinates: List<Pair<Double, Double>>,
    currentLocation: Pair<Double, Double>?,
    isLiveTracking: Boolean
): String {
    val coordsJson = StringBuilder("[")
    coordinates.forEachIndexed { i, pt ->
        coordsJson.append("[${pt.first},${pt.second}]")
        if (i < coordinates.size - 1) coordsJson.append(",")
    }
    coordsJson.append("]")

    val currentLocJs = if (currentLocation != null) {
        "[${currentLocation.first},${currentLocation.second}]"
    } else "null"

    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <link rel="stylesheet" href="https://appassets.androidplatform.net/assets/leaflet/leaflet.css" />
          <script src="https://appassets.androidplatform.net/assets/leaflet/leaflet.js"></script>
          <style>
            html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; background: #121214; }
            .leaflet-control-attribution { display: none !important; }
            .pulse-marker {
              width: 14px;
              height: 14px;
              background: #00E5FF;
              border: 2.5px solid #FFFFFF;
              border-radius: 50%;
              box-shadow: 0 0 10px rgba(0, 229, 255, 0.9);
            }
            .scrub-marker {
              width: 16px;
              height: 16px;
              background: #FC5200;
              border: 3px solid #FFFFFF;
              border-radius: 50%;
              box-shadow: 0 0 14px rgba(252, 82, 0, 1.0);
            }
          </style>
        </head>
        <body>
        <div id="map"></div>
        <script>
          var map = L.map('map', { zoomControl: false, attributionControl: false });
          L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
            maxZoom: 19,
            subdomains: 'abcd'
          }).addTo(map);

          var rawCoords = $coordsJson;
          var curLoc = $currentLocJs;
          var polyline = null;
          var userMarker = null;
          var scrubMarker = null;

          if (rawCoords && rawCoords.length > 0) {
            polyline = L.polyline(rawCoords, { color: '#FC5200', weight: 4.5, opacity: 0.95 }).addTo(map);
            map.fitBounds(polyline.getBounds(), { padding: [25, 25] });

            L.circleMarker(rawCoords[0], { radius: 6.5, fillColor: '#00E676', color: '#FFFFFF', weight: 2.5, fillOpacity: 1 }).addTo(map);
            L.circleMarker(rawCoords[rawCoords.length - 1], { radius: 6.5, fillColor: '#FF3B30', color: '#FFFFFF', weight: 2.5, fillOpacity: 1 }).addTo(map);
          } else if (curLoc) {
            map.setView(curLoc, 16);
          }

          setTimeout(function() { map.invalidateSize(); }, 250);
          setTimeout(function() { map.invalidateSize(); }, 600);

          if (curLoc) {
            var icon = L.divIcon({ className: 'pulse-marker', iconSize: [14, 14], iconAnchor: [7, 7] });
            userMarker = L.marker(curLoc, { icon: icon }).addTo(map);
          }

          function appendLivePoint(lat, lng) {
            var latlng = [lat, lng];
            if (!polyline) {
              polyline = L.polyline([latlng], { color: '#FC5200', weight: 4.5, opacity: 0.95 }).addTo(map);
              map.setView(latlng, 16);
            } else {
              polyline.addLatLng(latlng);
            }
            if (!userMarker) {
              var icon = L.divIcon({ className: 'pulse-marker', iconSize: [14, 14], iconAnchor: [7, 7] });
              userMarker = L.marker(latlng, { icon: icon }).addTo(map);
            } else {
              userMarker.setLatLng(latlng);
            }
          }

          function setScrubMarker(lat, lng) {
            if (lat === null || lng === null || typeof lat === 'undefined' || typeof lng === 'undefined') {
              if (scrubMarker) {
                map.removeLayer(scrubMarker);
                scrubMarker = null;
              }
              return;
            }
            var latlng = [lat, lng];
            if (!scrubMarker) {
              var icon = L.divIcon({ className: 'scrub-marker', iconSize: [16, 16], iconAnchor: [8, 8] });
              scrubMarker = L.marker(latlng, { icon: icon, zIndexOffset: 1000 }).addTo(map);
            } else {
              scrubMarker.setLatLng(latlng);
            }
          }
        </script>
        </body>
        </html>
    """.trimIndent()
}
