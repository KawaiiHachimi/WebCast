package app.webcast.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;

/**
 * Full-screen standby page: local HTTPS address, a three-step browser guide and a tips card.
 *
 * <p>Everything is painted onto one Canvas: a static dark gradient, the address panel, the steps and
 * a translucent "投屏小贴士" card. There is no animation, timer or render thread, which keeps the
 * the TV's old GPU idle while waiting for a sender. The page deliberately shows no QR code: the
 * sender runs in a desktop browser, so the URL is meant to be typed.</p>
 *
 * <p>All coordinates below are written in a 1920x1080 design space and multiplied by
 * {@link #scale} while drawing. The 96 px design margin (>= the required 48 px) keeps content away
 * from TV overscan. {@link #onSizeChanged} only re-computes rectangles, shaders and text metrics.
 * </p>
 *
 * <p>Public methods are safe to call from any thread; the view schedules its own repaint.</p>
 */
public final class IdleView extends View {
  // ---- Design-space layout (1920x1080), all values in design px -------------------------------
  private static final float DESIGN_W = 1920f;
  private static final float DESIGN_H = 1080f;

  private static final float SAFE_LEFT = 96f;
  private static final float SAFE_RIGHT = 1824f;

  private static final float BRAND_TOP = 100f;
  private static final float BRAND_HEIGHT = 48f;
  private static final float TITLE_BASELINE = 292f;
  private static final float SUBTITLE_BASELINE = 360f;

  // Address panel: roughly two thirds of the 1728 px content width.
  private static final float PANEL_LEFT = 96f;
  private static final float PANEL_TOP = 420f;
  private static final float PANEL_RIGHT = 1224f;
  private static final float PANEL_BOTTOM = 632f;
  private static final float PANEL_LABEL_BASELINE = 476f;
  private static final float PANEL_URL_BASELINE = 566f;

  private static final float STEP_FIRST_CENTER_Y = 696f;
  private static final float STEP_GAP = 84f;

  // Tips card on the right (former QR area).
  private static final float CARD_LEFT = 1280f;
  private static final float CARD_TOP = 200f;
  private static final float CARD_RIGHT = 1824f;
  private static final float CARD_BOTTOM = 840f;
  private static final float CARD_PAD = 44f;
  private static final float CARD_HEADER_BASELINE = 286f;
  private static final float CARD_SUBTITLE_BASELINE = 330f;
  private static final float CARD_DIVIDER_Y = 366f;
  private static final float TIP_FIRST_TOP = 396f;
  private static final float TIP_GAP = 108f;
  private static final int TIP_COUNT = 4;

  private static final float DIVIDER_Y = 918f;
  private static final float STATUS_BASELINE = 988f;

  // ---- Tips content ---------------------------------------------------------------------------
  private static final String[] TIP_TITLES = {
      "同一网络", "返回键停止", "画质可调", "1080p 60Hz"
  };
  private static final String[] TIP_DETAILS = {
      "电脑和电视连接同一个 Wi-Fi 或路由器，否则打不开上面的地址。",
      "投屏中按电视遥控器的返回键即可停止，画面会回到本页。",
      "电脑页面可选清晰度、帧率和码率；网络卡时用 720p。",
      "电视面板是 1920×1080 60Hz，电脑端选 1080p 即可满屏清晰。"
  };

  // ---- Palette --------------------------------------------------------------------------------
  private static final int COLOR_BG_TOP = 0xFF0E1D34;
  private static final int COLOR_BG_MID = 0xFF08111F;
  private static final int COLOR_BG_BOTTOM = 0xFF04070D;
  private static final int COLOR_GLOW = 0x332563EB;

  private static final int COLOR_TEXT = 0xFFF1F5F9;
  private static final int COLOR_ACCENT = 0xFF5EEAD4;
  private static final int COLOR_SUBTITLE = 0xFF94A3B8;
  private static final int COLOR_MUTED = 0xFF6C8FB5;

  private static final int COLOR_PANEL_FILL = 0xFF0A1729;
  private static final int COLOR_PANEL_BORDER = 0xFF2B5B8F;
  private static final int COLOR_PANEL_ACCENT = 0xFF38BDF8;
  private static final int COLOR_PANEL_HINT = 0xFF5B7A9E;
  private static final int COLOR_URL = 0xFFDCEFFF;

  private static final int COLOR_OFFLINE_TEXT = 0xFFFBBF24;
  private static final int COLOR_OFFLINE_LABEL = 0xFFE0B25A;
  private static final int COLOR_OFFLINE_BORDER = 0xFF7A5B22;
  private static final int COLOR_OFFLINE_ACCENT = 0xFFF59E0B;

  private static final int COLOR_STEP_CIRCLE = 0xFF123252;
  private static final int COLOR_STEP_RING = 0xFF2F6FA8;
  private static final int COLOR_STEP_NUMBER = 0xFF7DD3FC;

  private static final int COLOR_CARD_FILL = 0xE6122238;
  private static final int COLOR_CARD_BORDER = 0xFF2C4A6E;
  private static final int COLOR_CARD_TITLE = 0xFFF1F5F9;
  private static final int COLOR_CARD_SUBTITLE = 0xFF7E93AD;
  private static final int COLOR_CARD_DIVIDER = 0xFF24405F;
  private static final int COLOR_TIP_TITLE = 0xFFE2E8F0;
  private static final int COLOR_TIP_DETAIL = 0xFF94A3B8;
  private static final int COLOR_TIP_BADGE = 0xFF0F766E;

  private static final int COLOR_DIVIDER = 0xFF17263C;
  private static final int COLOR_DOT_LIVE = 0xFF34D399;
  private static final int COLOR_DOT_WAITING = 0xFFFBBF24;
  private static final int COLOR_DOT_ERROR = 0xFFF87171;
  private static final int COLOR_STATUS_LIVE = 0xFFA7F3D0;
  private static final int COLOR_STATUS_WAITING = 0xFFCBD5E1;
  private static final int COLOR_STATUS_ERROR = 0xFFFCA5A5;
  private static final int COLOR_FOOTER_INFO = 0xFF6B7F99;

  // ---- Paints and scratch objects (UI thread only, reused to avoid per-frame allocations) -----
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint metricsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bgPaint = new Paint();
  private final Paint glowPaint = new Paint();

  private final RectF panelRect = new RectF();
  private final RectF cardRect = new RectF();
  private final RectF scratchRect = new RectF();

  // ---- View geometry --------------------------------------------------------------------------
  private int viewW;
  private int viewH;
  private float scale = 1f;
  private float offsetX;
  private float offsetY;
  private Shader bgShader;
  private Shader glowShader;
  private String[][] tipDetailLines = new String[TIP_COUNT][];

  // ---- Content state, guarded by volatile because setters may run on network threads ----------
  private volatile String url;
  private volatile String model;
  private volatile String status;
  private volatile String version;
  private volatile boolean connected;

  private volatile String addressLabel = "网络状态";
  private volatile String addressText = "未连接网络，请检查电视 Wi-Fi";
  private volatile String addressHint = "Wi-Fi 未连接";
  private volatile boolean addressOffline = true;
  private volatile boolean statusError;
  private volatile String statusLine = "等待电脑连接";
  private volatile String infoLine = "Android TV  ·  1920×1080";

  public IdleView(Context context) {
    super(context);
    strokePaint.setStyle(Paint.Style.STROKE);
    bgPaint.setStyle(Paint.Style.FILL);
    glowPaint.setStyle(Paint.Style.FILL);
    setFocusable(false);
  }

  // ---- Public API used by MainActivity --------------------------------------------------------

  /**
   * Sets the LAN sender address, local IP and device model.
   *
   * @param url   full URL handed out by the web server (may be null when there is no LAN address)
   * @param ip    local IPv4 address, used as a fallback when {@code url} is null
   * @param model TV model shown in the footer
   */
  public void setInfo(String url, String ip, String model) {
    this.model = model;
    this.url = normalizeUrl(url, ip);
    refreshDerived();
    refresh();
  }

  /** Sets the connection state line, e.g. ("投屏中", true). */
  public void setStatus(String status, boolean connected) {
    this.status = status;
    this.connected = connected;
    refreshDerived();
    refresh();
  }

  /** Sets the app version shown in the footer (e.g. "1.0.0"). */
  public void setVersion(String version) {
    this.version = version;
    refreshDerived();
    refresh();
  }

  // ---- Setter plumbing ------------------------------------------------------------------------

  private static String normalizeUrl(String url, String ip) {
    if (url != null) {
      String trimmed = url.trim();
      if (trimmed.length() > 0) return trimmed;
    }
    if (ip != null) {
      String trimmed = ip.trim();
      if (trimmed.length() > 0) return "https://" + trimmed + ":8443/";
    }
    return null;
  }

  /** URL without the trailing slash, so the panel reads "https://192.168.6.169:8443". */
  private static String displayUrl(String raw) {
    String value = raw;
    while (value.length() > 1 && value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  private void refreshDerived() {
    String resolved = url;
    boolean offline = resolved == null || resolved.length() == 0;
    addressOffline = offline;
    if (offline) {
      addressLabel = "网络状态";
      addressText = "未连接网络，请检查电视 Wi-Fi";
      addressHint = "Wi-Fi 未连接";
    } else {
      addressLabel = "在电脑浏览器打开以下地址";
      addressText = displayUrl(resolved);
      addressHint = "HTTPS · 端口 8443";
    }

    String detail = status == null ? "" : status.trim();
    boolean error = false;
    if (connected) {
      // MainActivity may already phrase the detail as "电脑已连接，…"; avoid a doubled prefix.
      if (detail.length() == 0) statusLine = "电脑已连接";
      else if (detail.startsWith("电脑已连接")) statusLine = detail;
      else statusLine = "电脑已连接：" + detail;
    } else if (isFailure(detail)) {
      // Startup/network failures stay visible instead of being masked by the waiting text.
      error = true;
      statusLine = detail;
    } else {
      statusLine = "等待电脑连接";
    }
    statusError = error;

    String shownModel = model == null || model.trim().length() == 0 ? "Android TV" : model.trim();
    String shownVersion = displayVersion(version);
    infoLine = shownVersion.length() == 0
        ? shownModel + "  ·  1920×1080"
        : shownModel + "  ·  " + shownVersion + "  ·  1920×1080";
  }

  /** Accepts both "1.0.0" and "WebCast v1.0.0" and renders a single "v1.0.0". */
  private static String displayVersion(String raw) {
    if (raw == null) return "";
    String value = raw.trim();
    if (value.length() == 0) return "";
    if (value.regionMatches(true, 0, "WebCast", 0, 8)) {
      value = value.substring(8).trim();
      if (value.length() == 0) return "";
    }
    char first = value.charAt(0);
    return first == 'v' || first == 'V' ? value : "v" + value;
  }

  private static boolean isFailure(String detail) {
    return detail.indexOf("失败") >= 0 || detail.indexOf("错误") >= 0
        || detail.indexOf("异常") >= 0;
  }

  private void refresh() {
    if (Looper.myLooper() == Looper.getMainLooper()) invalidate();
    else postInvalidate();
  }

  // ---- Layout ---------------------------------------------------------------------------------

  @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    viewW = w;
    viewH = h;
    scale = Math.min(w / DESIGN_W, h / DESIGN_H);
    if (scale <= 0f) scale = 1f;
    offsetX = (w - DESIGN_W * scale) * 0.5f;
    offsetY = (h - DESIGN_H * scale) * 0.5f;
    layoutGeometry();
    invalidate();
  }

  private void layoutGeometry() {
    rect(panelRect, PANEL_LEFT, PANEL_TOP, PANEL_RIGHT, PANEL_BOTTOM);
    rect(cardRect, CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM);

    bgShader = new LinearGradient(0f, 0f, viewW * 0.42f, viewH,
        new int[] {COLOR_BG_TOP, COLOR_BG_MID, COLOR_BG_BOTTOM},
        new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    glowShader = new RadialGradient(viewW * 0.72f, viewH * 0.42f,
        Math.max(viewW, viewH) * 0.62f,
        new int[] {COLOR_GLOW, 0x141D4ED8, 0x00000000},
        new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

    // Tip details are static strings; wrapping only depends on the current scale/width.
    float detailSize = dx(23f);
    float maxWidth = dx(CARD_RIGHT - CARD_PAD) - dx(CARD_LEFT + CARD_PAD + 46f);
    for (int i = 0; i < TIP_COUNT; i++) {
      tipDetailLines[i] = wrapText(TIP_DETAILS[i], detailSize, false, maxWidth, 2);
    }
  }

  private float dx(float designPx) {
    return offsetX + designPx * scale;
  }

  private float dy(float designPx) {
    return offsetY + designPx * scale;
  }

  private void rect(RectF out, float left, float top, float right, float bottom) {
    out.set(dx(left), dy(top), dx(right), dy(bottom));
  }

  // ---- Drawing --------------------------------------------------------------------------------

  @Override protected void onDraw(Canvas canvas) {
    if (viewW <= 0 || viewH <= 0) return;
    bgPaint.setShader(bgShader);
    canvas.drawRect(0f, 0f, viewW, viewH, bgPaint);
    glowPaint.setShader(glowShader);
    canvas.drawRect(0f, 0f, viewW, viewH, glowPaint);

    drawBrand(canvas);
    drawHeadline(canvas);
    drawAddressPanel(canvas);
    drawSteps(canvas);
    drawTipsCard(canvas);
    drawFooter(canvas);
  }

  private void drawBrand(Canvas canvas) {
    float labelSize = dx(24f);
    String label = "WebCast · 局域网投屏";
    float left = dx(SAFE_LEFT);
    float top = dy(BRAND_TOP);
    float height = dx(BRAND_HEIGHT);
    scratchRect.set(left, top, left + dx(64f) + measure(label, labelSize, false), top + height);
    fillRoundRect(canvas, scratchRect, height * 0.5f, 0xFF12263F);

    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setColor(0xFF2DD4BF);
    canvas.drawCircle(left + dx(22f), top + height * 0.5f, dx(5f), fillPaint);
    text(canvas, label, left + dx(36f),
        centeredBaseline(top + height * 0.5f, labelSize, false),
        labelSize, 0xFF9FC0E6, false);
  }

  private void drawHeadline(Canvas canvas) {
    float titleSize = dx(96f);
    float titleBaseline = dy(TITLE_BASELINE);
    float titleX = dx(SAFE_LEFT);
    String first = "投屏到";
    String second = "这台电视";
    text(canvas, first, titleX, titleBaseline, titleSize, COLOR_TEXT, true);
    text(canvas, second, titleX + measure(first, titleSize, true) + dx(2f), titleBaseline,
        titleSize, COLOR_ACCENT, true);
    text(canvas, "在电脑浏览器打开下面的地址，点击开始投屏",
        dx(SAFE_LEFT), dy(SUBTITLE_BASELINE), dx(34f), COLOR_SUBTITLE, false);
  }

  private void drawAddressPanel(Canvas canvas) {
    boolean offline = addressOffline;
    float radius = dx(28f);
    fillRoundRect(canvas, panelRect, radius, COLOR_PANEL_FILL);
    strokeRoundRect(canvas, panelRect, radius, Math.max(1f, dx(2f)),
        offline ? COLOR_OFFLINE_BORDER : COLOR_PANEL_BORDER);

    // Left accent bar.
    scratchRect.set(panelRect.left, panelRect.top + dx(34f),
        panelRect.left + dx(8f), panelRect.bottom - dx(34f));
    fillRoundRect(canvas, scratchRect, dx(4f),
        offline ? COLOR_OFFLINE_ACCENT : COLOR_PANEL_ACCENT);

    float labelSize = dx(26f);
    text(canvas, addressLabel, panelRect.left + dx(46f), dy(PANEL_LABEL_BASELINE), labelSize,
        offline ? COLOR_OFFLINE_LABEL : COLOR_MUTED, false);

    // Top-right hint: port when online, Wi-Fi state when offline.
    float hintSize = dx(22f);
    String hint = addressHint;
    text(canvas, hint, panelRect.right - dx(40f) - measure(hint, hintSize, false),
        dy(PANEL_LABEL_BASELINE), hintSize,
        offline ? COLOR_OFFLINE_LABEL : COLOR_PANEL_HINT, false);

    float maxWidth = panelRect.width() - dx(46f) - dx(40f);
    if (offline) {
      textFitted(canvas, addressText, panelRect.left + dx(46f), dy(PANEL_URL_BASELINE), dx(54f),
          maxWidth, COLOR_OFFLINE_TEXT, true);
    } else {
      textFitted(canvas, addressText, panelRect.left + dx(46f), dy(PANEL_URL_BASELINE), dx(78f),
          maxWidth, COLOR_URL, true);
    }
  }

  private void drawSteps(Canvas canvas) {
    drawStep(canvas, 1, "Chrome 等浏览器打开地址", "");
    drawStep(canvas, 2, "首次访问可能提示不安全，点「高级 → 继续前往」", "");
    drawStep(canvas, 3, "点「开始投屏」", "");
  }

  private void drawStep(Canvas canvas, int number, String head, String tail) {
    float centerY = dy(STEP_FIRST_CENTER_Y + (number - 1) * STEP_GAP);
    float radius = dx(23f);
    float centerX = dx(SAFE_LEFT) + radius;

    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setColor(COLOR_STEP_CIRCLE);
    canvas.drawCircle(centerX, centerY, radius, fillPaint);
    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeWidth(Math.max(1f, dx(2f)));
    strokePaint.setColor(COLOR_STEP_RING);
    canvas.drawCircle(centerX, centerY, radius, strokePaint);

    float numberSize = dx(24f);
    String label = Integer.toString(number);
    text(canvas, label, centerX - measure(label, numberSize, true) * 0.5f,
        centeredBaseline(centerY, numberSize, true), numberSize, COLOR_STEP_NUMBER, true);

    float textSize = dx(34f);
    float textX = centerX + radius + dx(22f);
    float baseline = centeredBaseline(centerY, textSize, true);
    text(canvas, head, textX, baseline, textSize, 0xFFE2E8F0, true);
    if (tail.length() > 0) {
      text(canvas, tail, textX + measure(head, textSize, true), baseline, textSize,
          COLOR_SUBTITLE, false);
    }
  }

  private void drawTipsCard(Canvas canvas) {
    float radius = dx(40f);
    fillRoundRect(canvas, cardRect, radius, COLOR_CARD_FILL);
    strokeRoundRect(canvas, cardRect, radius, Math.max(1f, dx(2f)), COLOR_CARD_BORDER);

    float innerLeft = cardRect.left + dx(CARD_PAD);
    float innerRight = cardRect.right - dx(CARD_PAD);

    // Header.
    text(canvas, "投屏小贴士", innerLeft, dy(CARD_HEADER_BASELINE), dx(40f), COLOR_CARD_TITLE, true);
    text(canvas, "让画面更稳的 4 个小设置", innerLeft, dy(CARD_SUBTITLE_BASELINE), dx(23f),
        COLOR_CARD_SUBTITLE, false);

    // Divider under the header.
    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeWidth(Math.max(1f, dx(1.5f)));
    strokePaint.setColor(COLOR_CARD_DIVIDER);
    canvas.drawLine(innerLeft, dy(CARD_DIVIDER_Y), innerRight, dy(CARD_DIVIDER_Y), strokePaint);

    float badgeRadius = dx(17f);
    float badgeCenterX = innerLeft + badgeRadius;
    float titleSize = dx(28f);
    float detailSize = dx(23f);
    float detailX = innerLeft + dx(46f);
    float detailLineHeight = dx(30f);

    for (int i = 0; i < TIP_COUNT; i++) {
      float rowTop = dy(TIP_FIRST_TOP + i * TIP_GAP);
      float badgeCenterY = rowTop + dx(18f);

      fillPaint.setStyle(Paint.Style.FILL);
      fillPaint.setColor(COLOR_TIP_BADGE);
      canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, fillPaint);
      String number = Integer.toString(i + 1);
      float numberSize = dx(20f);
      text(canvas, number, badgeCenterX - measure(number, numberSize, true) * 0.5f,
          centeredBaseline(badgeCenterY, numberSize, true), numberSize, 0xFFFFFFFF, true);

      text(canvas, TIP_TITLES[i], detailX, rowTop + dx(28f), titleSize, COLOR_TIP_TITLE, true);

      String[] lines = tipDetailLines[i];
      if (lines != null) {
        for (int line = 0; line < lines.length; line++) {
          text(canvas, lines[line], detailX, rowTop + dx(62f) + line * detailLineHeight, detailSize,
              COLOR_TIP_DETAIL, false);
        }
      }
    }
  }

  private void drawFooter(Canvas canvas) {
    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeWidth(Math.max(1f, dx(1.5f)));
    strokePaint.setColor(COLOR_DIVIDER);
    canvas.drawLine(dx(SAFE_LEFT), dy(DIVIDER_Y), dx(SAFE_RIGHT), dy(DIVIDER_Y), strokePaint);

    boolean live = connected;
    boolean error = !live && statusError;
    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setColor(live ? COLOR_DOT_LIVE : error ? COLOR_DOT_ERROR : COLOR_DOT_WAITING);
    float dotX = dx(SAFE_LEFT) + dx(8f);
    float dotY = dy(STATUS_BASELINE - 10f);
    canvas.drawCircle(dotX, dotY, dx(7f), fillPaint);

    float statusSize = dx(30f);
    float infoSize = dx(24f);
    // Reserve room for the status side so a long model name can never push into it.
    String info = ellipsize(infoLine, infoSize, false,
        dx(SAFE_RIGHT) - dx(SAFE_LEFT) - dx(320f));
    float infoWidth = measure(info, infoSize, false);
    float infoX = dx(SAFE_RIGHT) - infoWidth;
    String line = ellipsize(statusLine, statusSize, false,
        infoX - (dotX + dx(24f)) - dx(40f));
    text(canvas, line, dotX + dx(24f), dy(STATUS_BASELINE), statusSize,
        live ? COLOR_STATUS_LIVE : error ? COLOR_STATUS_ERROR : COLOR_STATUS_WAITING, false);
    text(canvas, info, infoX, dy(STATUS_BASELINE), infoSize, COLOR_FOOTER_INFO, false);
  }

  // ---- Small drawing/measuring helpers ---------------------------------------------------------

  private void fillRoundRect(Canvas canvas, RectF rect, float radius, int color) {
    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setColor(color);
    canvas.drawRoundRect(rect, radius, radius, fillPaint);
  }

  private void strokeRoundRect(Canvas canvas, RectF rect, float radius, float width, int color) {
    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeWidth(width);
    strokePaint.setColor(color);
    canvas.drawRoundRect(rect, radius, radius, strokePaint);
  }

  private void text(Canvas canvas, String value, float x, float baseline, float size, int color,
      boolean bold) {
    textPaint.setTextSize(size);
    textPaint.setColor(color);
    textPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    canvas.drawText(value, x, baseline, textPaint);
  }

  private float measure(String value, float size, boolean bold) {
    metricsPaint.setTextSize(size);
    metricsPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    return metricsPaint.measureText(value);
  }

  private float centeredBaseline(float centerY, float size, boolean bold) {
    metricsPaint.setTextSize(size);
    metricsPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    Paint.FontMetrics metrics = metricsPaint.getFontMetrics();
    return centerY - (metrics.ascent + metrics.descent) * 0.5f;
  }

  private void textFitted(Canvas canvas, String value, float x, float baseline, float size,
      float maxWidth, int color, boolean bold) {
    float fitted = size;
    if (value != null && value.length() > 0) {
      float width = measure(value, size, bold);
      if (width > maxWidth && width > 0f) fitted = size * maxWidth / width;
    }
    text(canvas, value, x, baseline, fitted, color, bold);
  }

  private String ellipsize(String value, float size, boolean bold, float maxWidth) {
    if (value == null) return "";
    if (maxWidth <= 0f) return "";
    if (measure(value, size, bold) <= maxWidth) return value;
    String suffix = "…";
    int low = 0;
    int high = value.length();
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (measure(value.substring(0, mid) + suffix, size, bold) <= maxWidth) low = mid;
      else high = mid - 1;
    }
    return value.substring(0, low) + suffix;
  }

  /** Greedy character wrapping with Chinese punctuation breaks; up to {@code maxLines} lines. */
  private String[] wrapText(String text, float size, boolean bold, float maxWidth, int maxLines) {
    ArrayList<String> lines = new ArrayList<String>();
    int start = 0;
    int length = text.length();
    while (start < length && lines.size() < maxLines) {
      int end = start + 1;
      int lastFit = start;
      int lastBreak = -1;
      while (end <= length) {
        if (measure(text.substring(start, end), size, bold) > maxWidth) break;
        lastFit = end;
        if (isBreakChar(text.charAt(end - 1))) lastBreak = end;
        end++;
      }
      int cut = lastFit;
      if (end <= length && lastBreak > start) cut = lastBreak;
      if (cut <= start) cut = start + 1; // never loop on a single column-wide glyph
      // Hanging punctuation: keep "，" "。" etc. at the end of the line instead of the start.
      while (cut < length && isHangingPunctuation(text.charAt(cut))) cut++;
      lines.add(text.substring(start, cut).trim());
      start = cut;
      while (start < length && text.charAt(start) == ' ') start++;
    }
    if (start < length && lines.size() == maxLines) {
      int last = lines.size() - 1;
      lines.set(last, ellipsize(lines.get(last) + "…", size, bold, maxWidth));
    }
    return lines.toArray(new String[lines.size()]);
  }

  private static boolean isBreakChar(char value) {
    return value == ' ' || value == '，' || value == '。' || value == '；' || value == '、'
        || value == '：' || value == '！' || value == '？' || value == '）';
  }

  /** Punctuation that must not start a line; it hangs at the end of the previous line. */
  private static boolean isHangingPunctuation(char value) {
    return value == '，' || value == '。' || value == '；' || value == '、' || value == '：'
        || value == '！' || value == '？' || value == '）';
  }
}
