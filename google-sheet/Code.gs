    /**
    * Apps Script nhận dữ liệu từ app Quay Test và ghi vào Google Sheet.
    *
    * - Mỗi "sheet" trong app = 1 tab trong bảng tính này (mặc định tự theo tháng: 11_2026...).
    * - Tab chưa có → NHÂN BẢN từ tab "Mẫu" (nếu có) hoặc tab tháng gần nhất, rồi xoá trắng dữ liệu
    *   → giữ nguyên định dạng / font / kiểu bảng người dùng đã làm.
    * - Chỉ ghi các cột nghiệp vụ. ID + tên file (để chống trùng, xoá, ghép video) được lưu
    *   ẨN trong developer metadata của từng dòng — không hiện thành cột.
    * - Ghi THEO TÊN CỘT ở dòng 1 → cột xếp thứ tự nào cũng được.
    *
    * Sửa xong: Triển khai → Quản lý → ✏️ → Phiên bản mới → Triển khai.
    */
    const DEFAULT_TAB = 'Log';
    const TEMPLATE_TAB = 'Mẫu';
    const SCRIPT_VERSION = 25; // ...14: xoá app không xoá Drive, 15: năm/tháng/ngày + tên dd/MM/yy_MÃ, 16: chỉ tạo thư mục khi tải xong, 17: driveCheck, 18: video trên Drive luôn có dòng trên Sheet, 19: thông tin lưu trong mô tả file Drive + tự khôi phục dòng từ Drive, 20: tự xoá video quá 6 tháng, 21: thông tin sửa trên Sheet tự chép xuống mô tả video Drive, 22: không xoá dòng có video trên Drive + trả link Drive cho app, 23: chèn dòng đúng vị trí ngày giờ + tự sắp xếp lại tab, 24: 1 video = 1 dòng (tìm theo link Drive + gộp dòng trùng), 25: chỉ gộp dòng GIỐNG HỆT nhau
    const SEP_BG = '#d9ead3';     // nền dòng tách ngày (xanh nhạt)
    const SEP_FG = '#274e13';     // chữ dòng tách ngày (xanh đậm)
    const MD_SEP = 'qt_sep';      // đánh dấu ẩn: dòng này là dòng tách ngày, không phải lần quay
    const THU = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];
    const COL_PAD = 36;        // lề thêm cho icon kiểu cột + mũi tên lọc của bảng
    const COL_MIN = 70;        // cột không hẹp hơn mức này
    const HEADER_BG = '#356853';   // xanh lá tiêu đề
    const HEADER_FG = '#ffffff';   // chữ trắng

    // [Tên cột trên sheet, khoá dữ liệu app gửi lên, kiểu]
    const COLUMNS = [
      ['Ngày', 'date', 'date'],
      ['Tên khách', 'customer', 'text'],
      ['Mã mạch', 'board', 'code'],
      ['Mã máy', 'code', 'code'],
      ['Thời gian quay clip', 'time', 'code'],
      ['Người Test', 'staff', 'text'],
      ['Loại Máy', 'type', 'text'],
      ['Ngày tạo bảo hành', 'warranty_date', 'date'],
      ['Ghi Chú', 'note', 'text'],
    ];
    // Cột kỹ thuật của bản cũ → tự chuyển vào metadata rồi xoá cột
    const OLD_TECH_COLUMNS = ['Tên file', 'Thời lượng (giây)', 'Thiết bị', 'Ghi lúc', 'ID'];
    const MD_ID = 'qt_id';
    const MD_FILE = 'qt_file';

    function norm_(s) { return String(s || '').trim().toLowerCase(); }

    /** "16/11/2026" → Date (để cột kiểu Ngày hiển thị đúng). */
    function toDate_(s) {
      const m = String(s || '').match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
      return m ? new Date(+m[3], +m[2] - 1, +m[1]) : (s || '');
    }

    function header_(sh) {
      const lastCol = Math.max(sh.getLastColumn(), 1);
      return sh.getRange(1, 1, 1, lastCol).getValues()[0].map(norm_);
    }

    /**
    * Các dòng tách ngày của tab: {số dòng: true}.
    * Nhận theo dấu ẩn (metadata) HOẶC theo nội dung ô đầu bắt đầu bằng "▼"
    * (dòng bị kéo/dán tay có thể mất dấu ẩn).
    */
    function sepRows_(sh) {
      const out = {};
      sh.createDeveloperMetadataFinder().withKey(MD_SEP).find().forEach(m => {
        try { out[m.getLocation().getRow().getRow()] = true; } catch (e) {}
      });
      const last = sh.getLastRow();
      if (last >= 2) {
        sh.getRange(2, 1, last - 1, 1).getDisplayValues().forEach((v, k) => {
          if (String(v[0]).trim().indexOf('▼') === 0) out[k + 2] = true;
        });
      }
      return out;
    }

    /** Ngày (dd/MM/yyyy) của dòng dữ liệu cuối cùng trong tab, '' nếu chưa có. */
    function lastDay_(sh, dateCol) {
      const last = sh.getLastRow();
      if (last < 2 || dateCol < 1) return '';
      const v = sh.getRange(last, dateCol).getValue();
      if (v instanceof Date) {
        return Utilities.formatDate(v, SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone(), 'dd/MM/yyyy');
      }
      return String(v || '').replace(/^'/, '');
    }

    /**
    * Chèn dòng tách ngày tại dòng r: GỘP NGANG toàn bộ cột của bảng thành 1 ô,
    * nhãn "▼ Thứ Hai, 16/11/2026" căn giữa, nền xanh nhạt, chữ đậm, viền xanh đậm phía trên.
    */
    function writeSep_(sh, r, dayStr, pos, width) {
      const d = toDate_(dayStr);
      const label = '▼ ' + (d instanceof Date ? THU[d.getDay()] + ', ' : '') + dayStr;
      const cols = tableLastCol_(sh) || width;
      const rng = sh.getRange(r, 1, 1, cols);
      rng.clearContent();
      sh.getRange(r, 1).setNumberFormat('@').setValue(label);   // ô đầu: nhãn dạng chữ
      try { rng.merge(); } catch (e) { /* bảng không cho gộp → vẫn để nhãn ở ô đầu */ }
      rng.setHorizontalAlignment('center').setVerticalAlignment('middle')
        .setBackground(SEP_BG).setFontColor(SEP_FG).setFontWeight('bold')
        .setBorder(true, null, null, null, null, null, SEP_FG, SpreadsheetApp.BorderStyle.SOLID_MEDIUM);
      sh.getRange(r + ':' + r).addDeveloperMetadata(MD_SEP, '1');
    }

    /**
    * Dọn khoảng trống trong tab:
    *  - Dòng dữ liệu bị xoá trắng (không còn chữ nào) → xoá hẳn dòng.
    *  - Ngày không còn máy nào (dòng tách ngày mà bên dưới không còn dữ liệu) → xoá dòng tách ngày.
    * Trả về số dòng đã xoá.
    */
    function compact_(sh) {
      const last = sh.getLastRow();
      const lastCol = tableLastCol_(sh);
      if (last < 2 || lastCol < 1) return 0;
      const vals = sh.getRange(2, 1, last - 1, lastCol).getDisplayValues();
      const seps = sepRows_(sh);
      const del = {};
      // 1) Dòng dữ liệu trắng
      vals.forEach((v, k) => {
        const row = k + 2;
        if (!seps[row] && v.every(x => String(x).trim() === '')) del[row] = true;
      });
      // 2) Dòng tách ngày mà ngày đó không còn dữ liệu
      Object.keys(seps).map(Number).forEach(s => {
        let hasData = false;
        for (let j = s + 1; j <= last; j++) {
          if (seps[j]) break;
          if (!del[j]) { hasData = true; break; }
        }
        if (!hasData) del[s] = true;
      });
      const rows = Object.keys(del).map(Number).filter(r => r >= 2 && r <= last).sort((a, b) => b - a);
      rows.forEach(r => sh.deleteRow(r));   // xoá từ dưới lên để số dòng không lệch
      return rows.length;
    }

    /** Đọc metadata ẩn của cả tab: {số dòng: {id, file}} */
    function rowMeta_(sh) {
      const out = {};
      [[MD_ID, 'id'], [MD_FILE, 'file']].forEach(([key, field]) => {
        sh.createDeveloperMetadataFinder().withKey(key).find().forEach(m => {
          try {
            const r = m.getLocation().getRow().getRow();
            (out[r] = out[r] || {})[field] = m.getValue();
          } catch (e) { /* metadata không gắn vào dòng → bỏ qua */ }
        });
      });
      return out;
    }

    function findRowById_(sh, id) {
      const f = sh.createDeveloperMetadataFinder().withKey(MD_ID).withValue(String(id)).find();
      for (let i = 0; i < f.length; i++) {
        try { return f[i].getLocation().getRow().getRow(); } catch (e) {}
      }
      return 0;
    }

    /** Bản cũ có cột ID/Tên file/... → chuyển ID + file vào metadata ẩn, rồi xoá các cột kỹ thuật. */
    function migrate_(sh) {
      const header = header_(sh);
      const techIdx = OLD_TECH_COLUMNS.map(t => header.indexOf(norm_(t))).filter(i => i >= 0);
      if (techIdx.length === 0) return;
      const iId = header.indexOf(norm_('ID'));
      const iFile = header.indexOf(norm_('Tên file'));
      const last = sh.getLastRow();
      if (last >= 2 && (iId >= 0 || iFile >= 0)) {
        const vals = sh.getRange(2, 1, last - 1, header.length).getDisplayValues();
        vals.forEach((r, k) => {
          const row = sh.getRange((k + 2) + ':' + (k + 2));
          if (iId >= 0 && r[iId]) row.addDeveloperMetadata(MD_ID, r[iId]);
          if (iFile >= 0 && r[iFile]) row.addDeveloperMetadata(MD_FILE, r[iFile]);
        });
      }
      techIdx.sort((a, b) => b - a).forEach(i => sh.deleteColumn(i + 1)); // xoá từ phải sang trái
    }

    /**
    * Dọn tab cho gọn:
    *  - Tô dòng tiêu đề xanh lá, chữ trắng, đậm.
    *  - Xoá các cột thừa phía sau bảng (J, K, L...).
    *  - Canh độ rộng từng cột VỪA KHÍT nội dung dài nhất (giãn hoặc co tuỳ dữ liệu).
    */
    function tidy_(sh) {
      const lastCol = tableLastCol_(sh);
      if (lastCol < 1) return;

      // Chỉ xoá cột TRỐNG phía sau bảng (cột người dùng tự thêm có dữ liệu thì giữ)
      const keep = Math.max(lastCol, sh.getLastColumn());
      const extra = sh.getMaxColumns() - keep;
      if (extra > 0) sh.deleteColumns(keep + 1, extra);

      sh.getRange(1, 1, 1, lastCol)
        .setBackground(HEADER_BG).setFontColor(HEADER_FG).setFontWeight('bold');

      fitCols_(sh, 1, lastCol);
    }

    /** Cột cuối của bảng = cột nghiệp vụ xa nhất trên dòng tiêu đề (0 nếu không phải tab bảng). */
    function tableLastCol_(sh) {
      const header = header_(sh);
      let lastCol = 0;
      COLUMNS.forEach(([title]) => { lastCol = Math.max(lastCol, header.indexOf(norm_(title)) + 1); });
      return lastCol;
    }

    /** Canh độ rộng cột [from..to] vừa khít nội dung dài nhất (giãn hoặc co). */
    function fitCols_(sh, from, to) {
      for (let c = from; c <= to; c++) {
        sh.autoResizeColumn(c);
        sh.setColumnWidth(c, Math.max(COL_MIN, sh.getColumnWidth(c) + COL_PAD));
      }
    }

    /**
    * Tự chạy khi NGƯỜI DÙNG sửa trực tiếp trên Google Sheet (gõ, xoá nội dung, dán...):
    * canh lại độ rộng các cột vừa sửa → xoá chữ dài thì cột co lại, gõ chữ dài thì giãn ra.
    * (Simple trigger: chỉ cần Lưu là chạy, không cần triển khai.)
    */
    function onEdit(e) {
      try {
        const sh = e.range.getSheet();
        const lastCol = tableLastCol_(sh);
        if (lastCol < 1) return;                       // không phải tab bảng → bỏ qua
        const from = e.range.getColumn();
        if (from > lastCol) return;
        // Xoá trắng dòng / cả ngày → dọn dòng trống + dòng tách ngày không còn máy
        const removed = (e.value === undefined) ? compact_(sh) : 0;
        if (removed > 0) fitCols_(sh, 1, lastCol);
        else fitCols_(sh, from, Math.min(lastCol, from + e.range.getNumColumns() - 1));
      } catch (err) { /* không làm phiền người dùng khi lỗi */ }
    }

    // ======================= GOOGLE DRIVE =======================
  // Video lưu ở Drive của chủ script: "QuayTest Video/<năm>/Tháng MM/Ngày dd/dd/MM/yy_MÃ.mp4"
  const DRIVE_ROOT = 'QuayTest Video';

  /**
   * CHẠY TAY 1 LẦN trong trình sửa Apps Script (chọn hàm này → bấm ▶ Chạy) để Google
   * hỏi cấp quyền Google Drive + gọi dịch vụ ngoài. Không cần chạy lại sau đó.
   */
  function capQuyenDrive() {
    const root = folder_(null, DRIVE_ROOT);
    UrlFetchApp.fetch('https://www.googleapis.com/drive/v3/about?fields=user', {
      headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() }, muteHttpExceptions: true,
    });
    Logger.log('OK — đã có quyền Drive. Thư mục video: ' + root.getUrl());
  }

  function folder_(parent, name) {
    const it = parent ? parent.getFoldersByName(name) : DriveApp.getFoldersByName(name);
    if (it.hasNext()) return it.next();
    return parent ? parent.createFolder(name) : DriveApp.createFolder(name);
  }

  /**
   * Tạo phiên tải lên (resumable upload) của Drive API bằng quyền của script.
   * App nhận "uploadUrl" rồi PUT video thẳng lên Drive (không đi qua script → không giới hạn dung lượng).
   */
  /**
   * Thư mục + tên file trên Drive theo NGÀY QUAY:
   *   QuayTest Video/2026/Tháng 11/Ngày 16/16/11/26_VQR12345.mp4
   * (Tháng/Ngày có số 0 phía trước để Drive xếp đúng thứ tự.)
   * Trùng tên trong ngày (test lại cùng máy) → thêm giờ quay: 16/11/26_VQR12345_1430.mp4
   */
  /** Tìm thư mục con theo tên; create=false thì KHÔNG tạo (trả null nếu chưa có). */
  function sub_(parent, name, create) {
    if (!parent) return null;
    const it = parent.getFoldersByName(name);
    if (it.hasNext()) return it.next();
    return create ? parent.createFolder(name) : null;
  }

  /**
   * create=false: chỉ tính tên file (không tạo thư mục) — dùng lúc BẮT ĐẦU tải.
   * create=true : tạo năm/tháng/ngày nếu chưa có — dùng khi tải XONG để chuyển video vào.
   */
  function drivePlace_(d, create) {
    const m = String(d.date || '').match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
    const now = new Date();
    const dd = m ? ('0' + m[1]).slice(-2) : Utilities.formatDate(now, Session.getScriptTimeZone(), 'dd');
    const MM = m ? ('0' + m[2]).slice(-2) : Utilities.formatDate(now, Session.getScriptTimeZone(), 'MM');
    const yyyy = m ? m[3] : Utilities.formatDate(now, Session.getScriptTimeZone(), 'yyyy');
    const root = folder_(null, DRIVE_ROOT);
    const dayFolder = sub_(sub_(sub_(root, yyyy, create), 'Tháng ' + MM, create), 'Ngày ' + dd, create);

    const code = String(d.code || '').trim() || String(d.name || 'video').replace(/\.mp4$/i, '');
    const base = dd + '/' + MM + '/' + yyyy.slice(-2) + '_' + code;
    const exists = n => (dayFolder ? dayFolder.getFilesByName(n).hasNext() : false) ||
                        root.getFilesByName(n).hasNext();   // đang tải dở cùng tên
    let name = base + '.mp4';
    if (exists(name)) {
      const hhmm = String(d.time || '').replace(/[^0-9]/g, '').slice(0, 4);
      if (hhmm) name = base + '_' + hhmm + '.mp4';
      for (let i = 2; exists(name); i++) name = base + (hhmm ? '_' + hhmm : '') + '_' + i + '.mp4';
    }
    return { root: root, folder: dayFolder, name: name };
  }

  function driveUploadInit_(d) {
    // KHÔNG tạo thư mục ngày lúc này: tải tạm vào thư mục gốc, xong mới chuyển (tránh thư mục rỗng)
    const place = drivePlace_(d, false);
    const folder = place.root;
    d.name = place.name;
    const res = UrlFetchApp.fetch(
      'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,webViewLink', {
        method: 'post',
        contentType: 'application/json; charset=UTF-8',
        headers: {
          Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
          'X-Upload-Content-Type': String(d.mime || 'video/mp4'),
          'X-Upload-Content-Length': String(d.size || ''),
        },
        payload: JSON.stringify({ name: String(d.name || 'video.mp4'), parents: [folder.getId()] }),
        muteHttpExceptions: true,
      });
    const h = res.getHeaders();
    const url = h.Location || h.location;
    if (res.getResponseCode() !== 200 || !url) {
      return { ok: false, error: 'Drive ' + res.getResponseCode() + ': ' + res.getContentText().slice(0, 200) };
    }
    return { ok: true, uploadUrl: url, driveName: d.name };
  }

  /** Tìm dòng theo tên file video (metadata ẩn qt_file). */
  function findRowByFile_(sh, file) {
    const f = sh.createDeveloperMetadataFinder().withKey(MD_FILE).withValue(String(file)).find();
    for (let i = 0; i < f.length; i++) {
      try { return f[i].getLocation().getRow().getRow(); } catch (e) {}
    }
    return 0;
  }

  /** fileId Drive trong link ở ô Mã máy (RichTextValue) — '' nếu không có. */
  function linkId_(v) {
    if (!v) return '';
    const m = [v.getLinkUrl()].concat(v.getRuns().map(x => x.getLinkUrl())).join(' ').match(/\/d\/([\w-]{20,})/);
    return m ? m[1] : '';
  }

  /** Dòng có ô Mã máy gắn link tới video fileId (0 nếu không có). */
  function findRowByLink_(sh, fileId) {
    const col = header_(sh).indexOf(norm_('Mã máy')) + 1;
    const last = sh.getLastRow();
    if (col < 1 || last < 2) return 0;
    const rich = sh.getRange(2, col, last - 1, 1).getRichTextValues();
    for (let i = 0; i < rich.length; i++) if (linkId_(rich[i][0]) === String(fileId)) return i + 2;
    return 0;
  }

  /** Điền các ô CÒN TRỐNG của dòng r từ thông tin row (không ghi đè dữ liệu đã có); thêm tên file ẩn nếu thiếu. */
  function fillBlanks_(t, r, row) {
    const cur = t.sh.getRange(r, 1, 1, t.width).getDisplayValues()[0];
    COLUMNS.forEach(([, key, kind]) => {
      const c = t.pos[key];
      let v = row[key];
      if (!c || key === 'code' || v === undefined || v === null || String(v).trim() === '') return;
      if (String(cur[c - 1] || '').trim() !== '') return;
      if (kind === 'date') v = toDate_(v);
      else if (kind === 'code') v = "'" + v;
      t.sh.getRange(r, c).setValue(v);
    });
    if (row.file && !(rowMeta_(t.sh)[r] || {}).file) t.sh.getRange(r + ':' + r).addDeveloperMetadata(MD_FILE, String(row.file));
  }

  /**
   * Gộp dòng trùng — CHỈ khi các dòng cùng link 1 video Drive VÀ mọi ô giống hệt nhau.
   * Khác dù 1 ô (giờ, khách, ghi chú...) → giữ riêng (vd. mạch quay lại bảo hành là lần quay khác).
   * Giữ 1 dòng (ưu tiên dòng có tên file ẩn), xoá các bản sao. Trả số dòng đã xoá.
   */
  function dedupeTab_(sh) {
    const head = header_(sh);
    const codeCol = head.indexOf(norm_('Mã máy'));
    const last = sh.getLastRow();
    if (codeCol < 0 || last < 2) return 0;
    const width = sh.getLastColumn();
    const rich = sh.getRange(2, codeCol + 1, last - 1, 1).getRichTextValues();
    const vals = sh.getRange(2, 1, last - 1, width).getDisplayValues();
    const meta = rowMeta_(sh);
    const groups = {};
    rich.forEach((r, i) => {
      const id = linkId_(r[0]);
      if (!id) return;
      const key = id + '|' + JSON.stringify(vals[i].map(v => String(v).trim()));
      (groups[key] = groups[key] || []).push(i + 2);
    });
    const del = [];
    Object.keys(groups).forEach(k => {
      const rows = groups[k];
      if (rows.length < 2) return;
      rows.sort((a, b) => ((meta[b] || {}).file ? 1 : 0) - ((meta[a] || {}).file ? 1 : 0));
      del.push.apply(del, rows.slice(1));
    });
    del.sort((a, b) => b - a).forEach(r => sh.deleteRow(r));
    if (del.length) { try { compact_(sh); } catch (e) {} console.log('Gộp ' + del.length + ' dòng giống hệt ở tab ' + sh.getName()); }
    return del.length;
  }

  /** Gắn link video Drive vào ô "Mã máy" của dòng r. */
  function linkRow_(sh, r, fileId, code) {
    const col = header_(sh).indexOf(norm_('Mã máy')) + 1;
    if (col < 1) return false;
    const cell = sh.getRange(r, col);
    const text = cell.getDisplayValue() || String(code || '');
    if (!text) return false;
    const url = 'https://drive.google.com/file/d/' + fileId + '/view';
    cell.setRichTextValue(SpreadsheetApp.newRichTextValue().setText(text).setLinkUrl(url).build());
    return true;
  }

  /**
   * VIDEO ĐÃ LÊN DRIVE THÌ SHEET PHẢI CÓ DÒNG: tìm dòng theo ID rồi theo tên file;
   * không có (chưa gửi được / bị xoá tay) → ghi lại dòng từ thông tin app gửi kèm, rồi gắn link.
   * row = {id, file, sheet, date, customer, board, code, time, staff, type, warranty_date, note}
   */
  function ensureRow_(row, fileId) {
    const t = getTab_(row.sheet);
    let r = row.id ? findRowById_(t.sh, row.id) : 0;
    if (!r && row.file) r = findRowByFile_(t.sh, row.file);
    if (!r && fileId) r = findRowByLink_(t.sh, fileId);    // 1 video = 1 dòng: đã có dòng gắn link video này
    let created = false;
    if (!r) {
      try { compact_(t.sh); } catch (e) {}
      r = appendRow_(t, row);
      created = true;
    } else {
      fillBlanks_(t, r, row);                              // có dòng rồi → chỉ điền ô còn trống (vd. giờ quay)
    }
    const linked = fileId ? linkRow_(t.sh, r, fileId, row.code) : false;
    if (fileId) writeDesc_(fileId, row);   // Drive giữ bản gốc thông tin → Sheet mất dòng thì tạo lại được
    return { row: r, created: created, linked: linked };
  }

  /** Tải xong: chuyển video vào thư mục ngày + bảo đảm có dòng trên Sheet (gắn link vào ô Mã máy). */
  function driveUploadDone_(d) {
    if (!d.fileId) return { ok: false, error: 'thiếu fileId' };
    // Tải xong → giờ mới tạo năm/tháng/ngày (nếu chưa có) và chuyển video vào
    let moved = false;
    try {
      const file = DriveApp.getFileById(String(d.fileId));
      const place = drivePlace_(d, true);
      file.moveTo(place.folder);
      moved = true;
    } catch (e) { console.warn('Chuyển video vào thư mục ngày lỗi: ' + e); }

    // App mới gửi kèm thông tin dòng → thiếu dòng thì ghi lại
    if (d.row && d.row.sheet) {
      const res = ensureRow_(d.row, String(d.fileId));
      return { ok: true, moved: moved, linked: res.linked, created: res.created };
    }
    // App cũ: chỉ gắn link nếu tìm thấy dòng
    const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(String(d.sheet || DEFAULT_TAB));
    if (!sh || !d.id) return { ok: true, moved: moved, linked: false };
    const r = findRowById_(sh, d.id);
    if (!r) return { ok: true, linked: false };
    return { ok: true, linked: linkRow_(sh, r, String(d.fileId), d.code) };
  }

  /** Lấy fileId Drive từ link ở ô Mã máy của dòng có ID (hoặc tên file) — '' nếu không có. */
  function linkedFileId_(sheet, id, file) {
    try {
      const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(String(sheet));
      if (!sh) return '';
      let r = id ? findRowById_(sh, id) : 0;
      if (!r && file) r = findRowByFile_(sh, file);
      if (!r) return '';
      const col = header_(sh).indexOf(norm_('Mã máy')) + 1;
      const link = col > 0 ? (sh.getRange(r, col).getRichTextValue().getLinkUrl() || '') : '';
      const m = link.match(/\/d\/([\w-]{20,})/);
      return m ? m[1] : '';
    } catch (e) { return ''; }
  }

  /**
   * Kiểm tra video còn trên Drive không (app gọi trước khi tải lại video đã tải trước đó).
   * Mỗi mục {file, fileId, sheet, id, row}. Video CÒN trên Drive + có row → bảo đảm có dòng trên Sheet.
   * Trả {exists: {tên file: true/false}} — false = chưa có / đã vào thùng rác.
   */
  function driveCheck_(d) {
    const out = {};
    (d.items || []).forEach(it => {
      const id = String(it.fileId || '') || linkedFileId_(it.sheet, it.id, it.file);
      let exists = false;
      if (id) { try { exists = !DriveApp.getFileById(id).isTrashed(); } catch (e) { exists = false; } }
      out[String(it.file)] = exists;
      if (exists && it.row && it.row.sheet) {
        try { ensureRow_(it.row, id); } catch (e) { console.warn('ensureRow lỗi: ' + e); }
      }
    });
    return { ok: true, version: SCRIPT_VERSION, exists: out };
  }

  /**
   * Bù dòng thiếu: các video app biết là đã lên Drive nhưng Sheet không có dòng.
   * items [{row, fileId}] → video còn trên Drive thì ghi lại dòng + link. Trả số dòng đã ghi lại.
   */
  function ensureRows_(d) {
    let created = 0;
    const gone = [];
    const done = [];
    (d.items || []).forEach(it => {
      const row = it.row || {};
      if (!row.sheet) return;
      let exists = false;
      try { exists = !!it.fileId && !DriveApp.getFileById(String(it.fileId)).isTrashed(); } catch (e) { exists = false; }
      if (!exists) { gone.push(String(row.file || '')); return; }   // không còn trên Drive → không ghi
      try {
        if (ensureRow_(row, String(it.fileId)).created) created++;
        done.push(String(row.file || ''));
      } catch (e) { console.warn('ensureRow lỗi: ' + e); }
    });
    return { ok: true, version: SCRIPT_VERSION, created: created, gone: gone, done: done };
  }

  // ================= DRIVE GIỮ BẢN GỐC THÔNG TIN → TỰ KHÔI PHỤC DÒNG =================
  // Mỗi video trên Drive mang thông tin lần quay trong phần MÔ TẢ (Drive → chuột phải → Chi tiết).
  // Lỡ xoá dòng trên Sheet mà video còn trên Drive → dòng được tạo lại (lịch 30 phút / menu / khi app mở
  // Lịch sử quay). Muốn bỏ hẳn: xoá video trên Drive trước.
  const DESC_TAG = 'QTDATA:';
  const ROW_KEYS = ['id', 'file', 'sheet', 'date', 'time', 'customer', 'board', 'code', 'staff', 'type', 'warranty_date', 'note'];

  function writeDesc_(fileId, row) {
    try {
      const slim = {};
      ROW_KEYS.forEach(k => { if (row[k] !== undefined && row[k] !== null && row[k] !== '') slim[k] = String(row[k]); });
      const human = [
        'Mã máy: ' + (row.code || ''),
        'Ngày quay: ' + ((row.date || '') + ' ' + (row.time || '')).trim(),
        'Khách: ' + (row.customer || ''),
        'Người test: ' + (row.staff || ''),
        'Loại máy: ' + (row.type || ''),
        'Mã mạch: ' + (row.board || ''),
        'Ngày tạo bảo hành: ' + (row.warranty_date || ''),
        'Ghi chú: ' + (row.note || ''),
      ].join('\n');
      DriveApp.getFileById(String(fileId)).setDescription(human + '\n\n' + DESC_TAG + JSON.stringify(slim));
    } catch (e) { console.warn('Ghi mô tả Drive lỗi: ' + e); }
  }

  function readDesc_(file) {
    const d = String(file.getDescription() || '');
    const i = d.indexOf(DESC_TAG);
    if (i < 0) return null;
    try { return JSON.parse(d.slice(i + DESC_TAG.length)); } catch (e) { return null; }
  }

  /** Video cũ chưa có mô tả: dựng tối thiểu từ tên "dd/MM/yy_MÃ[_HHMM][_n].mp4" (hoặc ngày tạo file). */
  function rowFromName_(file) {
    const name = file.getName();
    const m = name.match(/^(\d{2})\/(\d{2})\/(\d{2})_(.+?)(?:_(\d{4}))?(?:_\d+)?\.mp4$/i);
    const tz = Session.getScriptTimeZone();
    const created = file.getDateCreated();
    return {
      id: 'drive-' + file.getId(),
      date: m ? (m[1] + '/' + m[2] + '/20' + m[3]) : Utilities.formatDate(created, tz, 'dd/MM/yyyy'),
      time: m && m[5] ? (m[5].slice(0, 2) + ':' + m[5].slice(2)) : '',
      code: m ? m[4] : name.replace(/\.mp4$/i, ''),
      note: 'Khôi phục từ video trên Drive (không có thông tin chi tiết)',
    };
  }

  /** Các fileId Drive đã gắn link trong cột Mã máy của tab. */
  function linkedIds_(sh) {
    const set = {};
    const col = header_(sh).indexOf(norm_('Mã máy')) + 1;
    const last = sh.getLastRow();
    if (col < 1 || last < 2) return set;
    sh.getRange(2, col, last - 1, 1).getRichTextValues().forEach(r => {
      const v = r[0];
      if (!v) return;
      [v.getLinkUrl()].concat(v.getRuns().map(x => x.getLinkUrl())).forEach(u => {
        const m = String(u || '').match(/\/d\/([\w-]{20,})/);
        if (m) set[m[1]] = true;
      });
    });
    return set;
  }

  /**
   * Quét video trong thư mục QuayTest Video (onlyTab = 'MM_yyyy' → chỉ tháng đó, nhanh hơn).
   * Video còn trên Drive (không ở thùng rác) mà tab không có dòng → tạo lại dòng + gắn link.
   * Trả về số dòng đã tạo lại.
   */
  function syncFromDrive_(onlyTab) {
    const root = folder_(null, DRIVE_ROOT);
    const files = [];
    const take = folder => {
      const it = folder.getFiles();
      while (it.hasNext()) {
        const f = it.next();
        if (/\.mp4$/i.test(f.getName()) || String(f.getMimeType()).indexOf('video/') === 0) files.push(f);
      }
    };
    const walk = folder => {
      take(folder);
      const sf = folder.getFolders();
      while (sf.hasNext()) walk(sf.next());
    };
    const mt = String(onlyTab || '').match(/^(\d{2})_(\d{4})$/);
    if (mt) {
      take(root);   // video tải xong nhưng chưa kịp chuyển vào thư mục ngày
      const monthFolder = sub_(sub_(root, mt[2], false), 'Tháng ' + mt[1], false);
      if (monthFolder) walk(monthFolder);
    } else {
      walk(root);
    }

    const tabs = {};
    let created = 0;
    files.forEach(f => {
      try {
        if (f.isTrashed()) return;
        const desc = readDesc_(f);
        const row = desc || rowFromName_(f);
        if (!row.sheet) {
          const m = String(row.date || '').match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
          row.sheet = m ? ('0' + m[2]).slice(-2) + '_' + m[3] : DEFAULT_TAB;
        }
        if (mt && row.sheet !== onlyTab) return;
        let c = tabs[row.sheet];
        if (!c) {
          const t = getTab_(row.sheet);
          c = tabs[row.sheet] = { t: t, linked: linkedIds_(t.sh) };
        }
        const id = f.getId();
        if (c.linked[id]) return;                 // đã có dòng gắn link video này
        let r = row.id ? findRowById_(c.t.sh, row.id) : 0;
        if (!r && row.file) r = findRowByFile_(c.t.sh, row.file);
        if (!r) {
          try { compact_(c.t.sh); } catch (e) {}
          r = appendRow_(c.t, row);
          created++;
        }
        linkRow_(c.t.sh, r, id, row.code);
        c.linked[id] = true;
        if (!desc) writeDesc_(id, row);           // lần sau nhận ra ngay theo ID
      } catch (e) { console.warn('Khôi phục ' + f.getName() + ' lỗi: ' + e); }
    });
    return created;
  }

  // ================= TỰ XOÁ VIDEO CŨ TRÊN DRIVE =================
  // Video quay quá KEEP_MONTHS tháng → bỏ vào thùng rác Drive (Drive tự xoá hẳn sau 30 ngày → hết tốn dung lượng).
  // Dòng trên Sheet GIỮ LẠI (tra bảo hành), chỉ bỏ link + ghi chú "video đã tự xoá".
  // Muốn đổi thời hạn: sửa số dưới đây → Lưu → Triển khai phiên bản mới.
  const KEEP_MONTHS = 6;

  /** Ngày quay của video: theo mô tả → theo tên "dd/MM/yy_..." → ngày tạo file. */
  function shotDate_(f, desc) {
    let s = desc && desc.date;
    if (!s) {
      const m = f.getName().match(/^(\d{2})\/(\d{2})\/(\d{2})_/);
      if (m) s = m[1] + '/' + m[2] + '/20' + m[3];
    }
    const m2 = String(s || '').match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
    return m2 ? new Date(+m2[3], +m2[2] - 1, +m2[1]) : f.getDateCreated();
  }

  /** Bỏ link video ở dòng trên Sheet + ghi chú đã xoá (dòng vẫn giữ). */
  function markVideoRemoved_(f, desc) {
    try {
      let sheet = desc && desc.sheet;
      if (!sheet) {
        const dt = shotDate_(f, desc);
        sheet = ('0' + (dt.getMonth() + 1)).slice(-2) + '_' + dt.getFullYear();
      }
      const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(String(sheet));
      if (!sh) return;
      const head = header_(sh);
      const col = head.indexOf(norm_('Mã máy')) + 1;
      const noteCol = head.indexOf(norm_('Ghi Chú')) + 1;
      const last = sh.getLastRow();
      if (col < 1 || last < 2) return;
      const id = f.getId();
      sh.getRange(2, col, last - 1, 1).getRichTextValues().forEach((r, i) => {
        const v = r[0];
        if (!v) return;
        const urls = [v.getLinkUrl()].concat(v.getRuns().map(x => x.getLinkUrl())).join(' ');
        if (urls.indexOf(id) < 0) return;
        sh.getRange(i + 2, col).setRichTextValue(SpreadsheetApp.newRichTextValue().setText(v.getText()).build());
        if (noteCol > 0) {
          const c = sh.getRange(i + 2, noteCol);
          const old = String(c.getValue() || '');
          const tag = 'Video đã tự xoá khỏi Drive (quá ' + KEEP_MONTHS + ' tháng)';
          if (old.indexOf(tag) < 0) c.setValue(old ? old + ' • ' + tag : tag);
        }
      });
    } catch (e) { console.warn('Bỏ link dòng lỗi: ' + e); }
  }

  /** Bỏ vào thùng rác mọi video quay quá KEEP_MONTHS tháng + dọn thư mục rỗng. Trả số video đã xoá. */
  function donVideoCu_() {
    const cutoff = new Date();
    cutoff.setMonth(cutoff.getMonth() - KEEP_MONTHS);
    cutoff.setHours(0, 0, 0, 0);
    let n = 0;
    const walk = folder => {
      const it = folder.searchFiles('trashed = false');
      while (it.hasNext()) {
        const f = it.next();
        if (!(/\.mp4$/i.test(f.getName()) || String(f.getMimeType()).indexOf('video/') === 0)) continue;
        const desc = readDesc_(f);
        if (shotDate_(f, desc) >= cutoff) continue;
        try {
          markVideoRemoved_(f, desc);
          f.setTrashed(true);
          n++;
        } catch (e) { console.warn('Xoá ' + f.getName() + ' lỗi: ' + e); }
      }
      const sf = folder.searchFolders('trashed = false');
      while (sf.hasNext()) {
        const c = sf.next();
        walk(c);
        // Thư mục ngày / tháng / năm đã rỗng → bỏ luôn
        if (!c.searchFiles('trashed = false').hasNext() && !c.searchFolders('trashed = false').hasNext()) {
          try { c.setTrashed(true); } catch (e) {}
        }
      }
    };
    walk(folder_(null, DRIVE_ROOT));
    if (n > 0) console.log('Đã xoá ' + n + ' video quá ' + KEEP_MONTHS + ' tháng');
    return n;
  }

  /** Mỗi ngày dọn 1 lần (gọi trong lịch 30 phút). */
  function donVideoCuMoiNgay_() {
    const p = PropertiesService.getScriptProperties();
    const today = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyyMMdd');
    if (p.getProperty('qt_clean_day') === today) return 0;
    p.setProperty('qt_clean_day', today);
    return donVideoCu_();
  }

  /**
   * SHEET → DRIVE: thông tin trên Sheet (kể cả sửa / bổ sung tay) chép xuống mô tả video Drive,
   * để lỡ xoá dòng thì dòng tạo lại có ĐÚNG thông tin mới nhất. onlyTab = chỉ 1 tab (nhanh hơn).
   * Chỉ ghi khi khác mô tả cũ. Trả số video đã cập nhật mô tả.
   */
  function sheetToDesc_(onlyTab) {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    let n = 0;
    ss.getSheets().forEach(sh => {
      const name = sh.getName();
      if (onlyTab ? name !== onlyTab : !(/^\d{2}_\d{4}$/.test(name) || name === DEFAULT_TAB)) return;
      const head = header_(sh);
      const codeCol = head.indexOf(norm_('Mã máy'));
      const last = sh.getLastRow();
      if (codeCol < 0 || last < 2) return;
      const idx = {};
      COLUMNS.forEach(([t, k]) => { idx[k] = head.indexOf(norm_(t)); });
      const vals = sh.getRange(2, 1, last - 1, head.length).getDisplayValues();
      const rich = sh.getRange(2, codeCol + 1, last - 1, 1).getRichTextValues();
      const meta = rowMeta_(sh);
      rich.forEach((r, i) => {
        const v = r[0];
        if (!v) return;
        const m = [v.getLinkUrl()].concat(v.getRuns().map(x => x.getLinkUrl())).join(' ').match(/\/d\/([\w-]{20,})/);
        if (!m) return;
        try {
          const f = DriveApp.getFileById(m[1]);
          if (f.isTrashed()) return;
          const old = readDesc_(f) || {};
          const row = { sheet: name };
          COLUMNS.forEach(([, k]) => { if (idx[k] >= 0) row[k] = String(vals[i][idx[k]] || '').replace(/^'/, ''); });
          const md = meta[i + 2] || {};
          row.id = md.id || old.id || '';
          row.file = md.file || old.file || '';
          if (ROW_KEYS.some(k => String(row[k] || '') !== String(old[k] || ''))) { writeDesc_(m[1], row); n++; }
        } catch (e) { /* video không mở được → bỏ qua */ }
      });
    });
    if (n > 0) console.log('Đã cập nhật mô tả ' + n + ' video từ Sheet');
    return n;
  }

  /** Lịch tự chạy 30 phút / lần (tạo 1 lần, tự động khi app gọi script). */
  function ensureTrigger_() {
    try {
      const p = PropertiesService.getScriptProperties();
      if (p.getProperty('qt_trigger') === '1') return;
      if (!ScriptApp.getProjectTriggers().some(t => t.getHandlerFunction() === 'khoiPhucTuDrive')) {
        ScriptApp.newTrigger('khoiPhucTuDrive').timeBased().everyMinutes(30).create();
      }
      p.setProperty('qt_trigger', '1');
    } catch (e) { console.warn('Không tạo được lịch tự khôi phục: ' + e); }
  }

  /**
   * CHẠY 1 LẦN trong trình sửa Apps Script sau khi dán code (để cấp quyền + bật lịch 30 phút).
   * Sau đó tự chạy theo lịch: video còn trên Drive mà Sheet mất dòng → tạo lại dòng.
   */
  function khoiPhucTuDrive() {
    ensureTrigger_();
    const lock = LockService.getScriptLock();
    if (!lock.tryLock(30000)) return 0;
    try {
      // Dọn video quá hạn TRƯỚC (video đã vào thùng rác thì không tạo lại dòng)
      try { donVideoCuMoiNgay_(); } catch (e) { console.warn('Dọn video cũ lỗi: ' + e); }
      // Sheet → Drive: chép thông tin mới nhất xuống mô tả video (tab tháng này mỗi lần, mọi tab mỗi ngày)
      try {
        const p = PropertiesService.getScriptProperties();
        const today = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyyMMdd');
        if (p.getProperty('qt_desc_day') !== today) { sheetToDesc_(null); p.setProperty('qt_desc_day', today); }
        else sheetToDesc_(Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'MM_yyyy'));
      } catch (e) { console.warn('Chép thông tin xuống Drive lỗi: ' + e); }
      const n = syncFromDrive_(null);
      console.log('Đã tạo lại ' + n + ' dòng từ video trên Drive');
      try {
        const p = PropertiesService.getScriptProperties();
        const today = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyyMMdd');
        if (p.getProperty('qt_sort_day') !== today) { sortTabs_(null); p.setProperty('qt_sort_day', today); }
        else sortTabs_(Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'MM_yyyy'));
      } catch (e) { console.warn('Sắp xếp lỗi: ' + e); }
      return n;
    } finally { lock.releaseLock(); }
  }

  /** Menu trên Google Sheet: Quay Test → Khôi phục dòng từ Drive. */
  function onOpen() {
    SpreadsheetApp.getUi().createMenu('Quay Test')
      .addItem('Khôi phục dòng từ Drive', 'khoiPhucTuDriveMenu')
      .addItem('Sắp xếp lại theo ngày giờ', 'sapXepMenu')
      .addItem('Xoá video quá ' + KEEP_MONTHS + ' tháng ngay', 'donVideoCuMenu')
      .addToUi();
  }
  function donVideoCuMenu() {
    const ui = SpreadsheetApp.getUi();
    const ok = ui.alert('Xoá video quá ' + KEEP_MONTHS + ' tháng?',
      'Video quay quá ' + KEEP_MONTHS + ' tháng sẽ vào thùng rác Google Drive (Drive tự xoá hẳn sau 30 ngày). ' +
      'Dòng trên Sheet vẫn giữ.', ui.ButtonSet.OK_CANCEL);
    if (ok !== ui.Button.OK) return;
    const lock = LockService.getScriptLock();
    if (!lock.tryLock(30000)) { ui.alert('Script đang bận, thử lại sau ít phút.'); return; }
    let n = 0;
    try { n = donVideoCu_(); } finally { lock.releaseLock(); }
    ui.alert(n > 0 ? 'Đã xoá ' + n + ' video quá ' + KEEP_MONTHS + ' tháng.' : 'Không có video nào quá ' + KEEP_MONTHS + ' tháng.');
  }
  function sapXepMenu() {
    const lock = LockService.getScriptLock();
    if (!lock.tryLock(30000)) { SpreadsheetApp.getUi().alert('Script đang bận, thử lại sau ít phút.'); return; }
    let n = 0;
    try { n = sortTabs_(null); } finally { lock.releaseLock(); }
    SpreadsheetApp.getUi().alert(n > 0 ? 'Đã sắp xếp lại ' + n + ' tab theo ngày giờ quay.' : 'Các tab đã đúng thứ tự ngày giờ.');
  }
  function khoiPhucTuDriveMenu() {
    const n = khoiPhucTuDrive();
    SpreadsheetApp.getUi().alert(n > 0 ? 'Đã tạo lại ' + n + ' dòng từ video trên Drive.' : 'Không thiếu dòng nào — Sheet đã khớp với Drive.');
  }

  /** Dòng r có link Drive ở ô Mã máy → bỏ file đó vào thùng rác. */
  function trashLinkedVideo_(sh, r) {
    const col = header_(sh).indexOf(norm_('Mã máy')) + 1;
    if (col < 1) return;
    const link = sh.getRange(r, col).getRichTextValue().getLinkUrl() || '';
    const m = link.match(/\/d\/([\w-]{20,})/);
    if (m) DriveApp.getFileById(m[1]).setTrashed(true);
  }

  /** Tab mẫu để nhân bản: "Mẫu" nếu có, không thì tab tháng (MM_yyyy) mới nhất. */
    function templateTab_(ss) {
      const t = ss.getSheetByName(TEMPLATE_TAB);
      if (t) return t;
      let best = null, bestKey = -1;
      ss.getSheets().forEach(s => {
        const m = s.getName().match(/^(\d{2})_(\d{4})$/);
        if (m) { const k = +m[2] * 100 + +m[1]; if (k > bestKey) { bestKey = k; best = s; } }
      });
      return best;
    }

    /** Lấy tab (tạo nếu chưa có) + vị trí cột {key: cột 1-based}. */
    function getTab_(name) {
      const ss = SpreadsheetApp.getActiveSpreadsheet();
      const tab = String(name || DEFAULT_TAB).trim() || DEFAULT_TAB;
      let sh = ss.getSheetByName(tab);

      if (!sh) {
        const tpl = templateTab_(ss);
        if (tpl) {
          // Nhân bản → giữ định dạng/font/kiểu bảng; xoá dữ liệu + metadata cũ
          sh = ss.insertSheet(tab, { template: tpl });
          // MỖI BƯỚC DỌN CHẠY RIÊNG: bảng (Table) của Google không cho vài thao tác (gộp/gỡ gộp...)
          // → bước nào lỗi thì bỏ qua, KHÔNG được làm hỏng việc ghi dòng đầu tiên của tháng.
          const step = (f) => { try { f(); } catch (e) { console.warn('Dọn tab mới: ' + e); } };
          step(() => migrate_(sh));
          const n = sh.getLastRow() - 1;
          if (n >= 1) {
            const body = () => sh.getRange(2, 1, n, sh.getLastColumn());
            step(() => body().breakApart());                       // gỡ ô gộp của dòng tách ngày cũ
            let cleared = false;
            step(() => { body().clearContent(); cleared = true; }); // xoá dữ liệu tháng trước
            if (!cleared) step(() => sh.deleteRows(2, n));          // không xoá được nội dung → xoá hẳn dòng
            step(() => body().setBackground(null).setFontColor(null).setFontWeight('normal'));
            step(() => body().setHorizontalAlignment(null));
            step(() => body().setBorder(false, false, false, false, false, false));
            // Cột Ngày trở lại kiểu ngày (ô nhãn dòng tách ngày đã bị ép kiểu chữ)
            step(() => {
              const iDate = header_(sh).indexOf(norm_('Ngày'));
              if (iDate >= 0 && sh.getMaxRows() >= 2) sh.getRange(2, iDate + 1, sh.getMaxRows() - 1, 1).setNumberFormat('dd/MM/yyyy');
            });
          }
          [MD_ID, MD_FILE, MD_SEP].forEach(k => step(() =>
            sh.createDeveloperMetadataFinder().withKey(k).find().forEach(m => m.remove())));
        } else {
          sh = ss.insertSheet(tab);
        }
      }
      migrate_(sh);

      if (sh.getLastRow() === 0) { // tab trắng tinh (không có mẫu) → tạo tiêu đề xanh lá chữ trắng
        sh.getRange(1, 1, 1, COLUMNS.length).setValues([COLUMNS.map(c => c[0])])
          .setFontWeight('bold').setBackground(HEADER_BG).setFontColor(HEADER_FG);
        sh.setFrozenRows(1);
      }
      const header = header_(sh);
      const pos = {};
      let next = header.length;
      COLUMNS.forEach(([title, key]) => {
        let i = header.indexOf(norm_(title));
        if (i < 0) {
          next += 1;
          sh.getRange(1, next).setValue(title).setFontWeight('bold')
            .setBackground(HEADER_BG).setFontColor(HEADER_FG);
          i = next - 1;
        }
        pos[key] = i + 1;
      });
      return { sh: sh, pos: pos, width: Math.max(next, header.length) };
    }

    // ================= SẮP XẾP THEO NGÀY + GIỜ QUAY =================

    /** Khoá sắp xếp "yyyyMMddHHmmss" từ ngày "dd/MM/yyyy" + giờ "HH:mm[:ss]" (thiếu giờ → cuối ngày). */
    function rowKey_(dateStr, timeStr) {
      const m = String(dateStr || '').replace(/^'/, '').match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/);
      if (!m) return '';
      const t = String(timeStr || '').replace(/^'/, '').match(/(\d{1,2}):(\d{2})(?::(\d{2}))?/);
      const hms = t ? ('0' + t[1]).slice(-2) + t[2] + (t[3] || '00') : '999999';
      return m[3] + ('0' + m[2]).slice(-2) + ('0' + m[1]).slice(-2) + hms;
    }

    /** "20261117" → "17/11/2026" */
    function dayStr_(day) { return day.slice(6, 8) + '/' + day.slice(4, 6) + '/' + day.slice(0, 4); }

    /** Cấu trúc tab: [{r, sep, key, day}] — dòng tách ngày (sep) lấy ngày từ nhãn "▼ Thứ…, dd/MM/yyyy". */
    function layout_(sh, pos) {
      const last = sh.getLastRow();
      if (last < 2) return [];
      const tz = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
      const seps = sepRows_(sh);
      const n = last - 1;
      const dates = sh.getRange(2, pos.date, n, 1).getValues();
      const times = sh.getRange(2, pos.time, n, 1).getDisplayValues();
      const firsts = sh.getRange(2, 1, n, 1).getDisplayValues();
      const out = [];
      for (let i = 0; i < n; i++) {
        const r = i + 2;
        if (seps[r]) {
          const m = String(firsts[i][0]).match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/);
          out.push({ r: r, sep: true, day: m ? m[3] + ('0' + m[2]).slice(-2) + ('0' + m[1]).slice(-2) : '' });
          continue;
        }
        const dv = dates[i][0];
        const ds = dv instanceof Date ? Utilities.formatDate(dv, tz, 'dd/MM/yyyy') : String(dv || '');
        const key = rowKey_(ds, times[i][0]);
        out.push({ r: r, sep: false, key: key, day: key.slice(0, 8) });
      }
      return out;
    }

    /** Vị trí chèn dòng có khoá [key]: {p: số dòng, needSep: cần dòng tách ngày, append: ghi nối cuối}. */
    function insertPos_(sh, pos, key) {
      const append = { p: sh.getLastRow() + 1, append: true };
      const L = layout_(sh, pos);
      const data = L.filter(x => !x.sep && x.key);
      const day = key.slice(0, 8);
      if (!key) return Object.assign(append, { needSep: false });
      const next = data.find(x => x.key > key);
      if (!next) {
        const prev = data.length ? data[data.length - 1] : null;
        return Object.assign(append, { needSep: !prev || prev.day !== day });
      }
      if (next.day === day) return { p: next.r, needSep: false };
      // Dòng kế tiếp thuộc ngày sau → chèn ngay trước dòng tách ngày của ngày đó
      let at = next.r;
      const prevData = data.filter(x => x.r < next.r).pop();
      const sepAbove = L.filter(x => x.sep && x.r < next.r && (!prevData || x.r > prevData.r)).shift();
      if (sepAbove) at = sepAbove.r;
      return { p: at, needSep: !prevData || prevData.day !== day };
    }

    /**
     * Ghi 1 dòng ĐÚNG VỊ TRÍ theo ngày + giờ quay (dòng tạo lại / gửi bù không bị dồn xuống cuối).
     * Ngày chưa có trong tab → chèn kèm dòng tách ngày. Trả về số dòng đã ghi.
     */
    function appendRow_(t, d) {
      const row = new Array(t.width).fill('');
      COLUMNS.forEach(([, key, kind]) => {
        let v = d[key];
        if (v === undefined || v === null) v = '';
        if (kind === 'date') v = toDate_(v);
        else if (kind === 'code' && v !== '') v = "'" + v; // giữ số 0 đầu, không tự đổi kiểu
        row[t.pos[key] - 1] = v;
      });
      const day = String(d.date || '');
      const ip = insertPos_(t.sh, t.pos, rowKey_(day, d.time));
      const withSep = ip.needSep && !!day;
      let r = ip.p;
      if (!ip.append) t.sh.insertRowsBefore(r, withSep ? 2 : 1);
      else if (t.sh.getMaxRows() < r + (withSep ? 1 : 0)) t.sh.insertRowsAfter(t.sh.getMaxRows(), r + (withSep ? 1 : 0) - t.sh.getMaxRows());
      if (withSep) { writeSep_(t.sh, r, day, t.pos, t.width); r += 1; }
      const rng = t.sh.getRange(r, 1, 1, row.length);
      try { rng.breakApart(); } catch (e) {}
      rng.setBackground(null).setFontColor(null).setFontWeight('normal').setHorizontalAlignment(null)
        .setBorder(false, false, false, false, false, false);
      dateFormats_(t, r, 1);
      rng.setValues([row]);
      const rowRange = t.sh.getRange(r + ':' + r);
      if (d.id) rowRange.addDeveloperMetadata(MD_ID, String(d.id));
      if (d.file) rowRange.addDeveloperMetadata(MD_FILE, String(d.file));
      try { tidy_(t.sh); } catch (e) { /* canh cột lỗi không ảnh hưởng việc ghi */ }
      return r;
    }

    /** Cột ngày của n dòng từ r về đúng định dạng ngày (dòng chèn có thể thừa hưởng kiểu chữ của dòng tách ngày). */
    function dateFormats_(t, r, n) {
      COLUMNS.forEach(([, key, kind]) => {
        if (kind === 'date' && t.pos[key]) t.sh.getRange(r, t.pos[key], n, 1).setNumberFormat('dd/MM/yyyy');
      });
    }

    /**
     * Sắp xếp lại cả tab theo ngày + giờ quay (mới nhất ở dưới, như lúc ghi), dựng lại dòng tách ngày.
     * Giữ nguyên: mọi cột (kể cả cột tự thêm), link video ở ô Mã máy, ID/tên file ẩn.
     * Chỉ làm khi tab đang lộn xộn. Trả true nếu đã sắp xếp.
     */
    function sortTab_(sh) {
      if (header_(sh).indexOf(norm_('Mã máy')) < 0) return false;
      const t = getTab_(sh.getName());
      const L = layout_(sh, t.pos);
      // Đã đúng: khoá không giảm dần + mỗi ngày mở đầu bằng đúng dòng tách ngày của ngày đó
      let ok = true, prevKey = '', curSep = null;
      for (const x of L) {
        if (x.sep) { curSep = x.day; continue; }
        if (!x.key) continue;
        if (x.key < prevKey || x.day !== curSep) { ok = false; break; }
        prevKey = x.key;
      }
      if (ok) return false;

      const last = sh.getLastRow();
      const width = Math.max(sh.getLastColumn(), t.width);
      const n = last - 1;
      const vals = sh.getRange(2, 1, n, width).getValues();
      const rich = sh.getRange(2, t.pos.code, n, 1).getRichTextValues();
      const meta = rowMeta_(sh);
      const isSep = {};
      L.forEach(x => { if (x.sep) isSep[x.r] = true; });
      const entries = [];
      L.forEach((x, k) => {
        if (x.sep) return;
        const i = x.r - 2;
        if (vals[i].every(v => String(v).trim() === '')) return;   // dòng trắng → bỏ
        const v = rich[i][0];
        const link = v ? [v.getLinkUrl()].concat(v.getRuns().map(q => q.getLinkUrl())).filter(Boolean)[0] || '' : '';
        const md = meta[x.r] || {};
        entries.push({ key: x.key, vals: vals[i], text: v ? v.getText() : '', link: link, id: md.id, file: md.file, k: k });
      });
      // Khoá rỗng (không có ngày) xuống cuối; cùng khoá giữ thứ tự cũ
      entries.sort((a, b) => (a.key || '~') < (b.key || '~') ? -1 : (a.key || '~') > (b.key || '~') ? 1 : a.k - b.k);

      // Dọn thân bảng rồi ghi lại theo thứ tự
      const body = sh.getRange(2, 1, n, width);
      try { body.breakApart(); } catch (e) {}
      body.clearContent();
      body.setBackground(null).setFontColor(null).setFontWeight('normal').setHorizontalAlignment(null)
        .setBorder(false, false, false, false, false, false);
      [MD_ID, MD_FILE, MD_SEP].forEach(key =>
        sh.createDeveloperMetadataFinder().withKey(key).find().forEach(m => { try { m.remove(); } catch (e) {} }));
      const codeCols = {};
      COLUMNS.forEach(([, key, kind]) => { if (kind === 'code' && t.pos[key]) codeCols[t.pos[key] - 1] = true; });
      // Đủ chỗ cho dòng dữ liệu + dòng tách ngày (có thể nhiều hơn trước)
      const days = {};
      entries.forEach(e => { if (e.key) days[e.key.slice(0, 8)] = true; });
      const need = 1 + entries.length + Object.keys(days).length;
      if (sh.getMaxRows() < need) sh.insertRowsAfter(sh.getMaxRows(), need - sh.getMaxRows());
      let r = 2, curDay = null;
      entries.forEach(e => {
        const day = e.key ? e.key.slice(0, 8) : '';
        if (day && day !== curDay) { writeSep_(sh, r, dayStr_(day), t.pos, width); r++; curDay = day; }
        const rng = sh.getRange(r, 1, 1, width);
        try { rng.breakApart(); } catch (er) {}
        rng.setBackground(null).setFontColor(null).setFontWeight('normal').setHorizontalAlignment(null)
          .setBorder(false, false, false, false, false, false);
        sh.getRange(r, 1).setNumberFormat('General');
        dateFormats_(t, r, 1);
        rng.setValues([e.vals.map((v, c) => (codeCols[c] && typeof v === 'string' && v !== '') ? "'" + v : v)]);
        if (e.link) {
          sh.getRange(r, t.pos.code).setRichTextValue(
            SpreadsheetApp.newRichTextValue().setText(e.text || String(e.vals[t.pos.code - 1] || '')).setLinkUrl(e.link).build());
        }
        const rowRange = sh.getRange(r + ':' + r);
        if (e.id) rowRange.addDeveloperMetadata(MD_ID, String(e.id));
        if (e.file) rowRange.addDeveloperMetadata(MD_FILE, String(e.file));
        r++;
      });
      try { compact_(sh); tidy_(sh); } catch (e) {}
      console.log('Đã sắp xếp lại tab ' + sh.getName());
      return true;
    }

    /** Sắp xếp lại các tab đang lộn xộn (onlyTab = chỉ 1 tab). Trả số tab đã sắp xếp. */
    function sortTabs_(onlyTab) {
      let n = 0;
      SpreadsheetApp.getActiveSpreadsheet().getSheets().forEach(sh => {
        const name = sh.getName();
        if (onlyTab ? name !== onlyTab : !(/^\d{2}_\d{4}$/.test(name) || name === DEFAULT_TAB)) return;
        try {
          const merged = dedupeTab_(sh);
          if (sortTab_(sh) || merged) n++;
        } catch (e) { console.warn('Sắp xếp tab ' + name + ' lỗi: ' + e); }
      });
      return n;
    }

    function doPost(e) {
      const lock = LockService.getScriptLock();
      lock.waitLock(20000);
      try {
        const d = JSON.parse(e.postData.contents);

        // ===== Google Drive: xin link tải lên 1 lần cho 1 video =====
        if (d.action === 'uploadInit') return json(driveUploadInit_(d));
        // ===== Google Drive: tải xong → gắn link video vào ô Mã máy =====
        if (d.action === 'uploadDone') { ensureTrigger_(); return json(driveUploadDone_(d)); }
        // ===== Google Drive: kiểm tra video còn trên Drive / bù dòng thiếu trên Sheet =====
        if (d.action === 'driveCheck') return json(driveCheck_(d));
        if (d.action === 'ensureRows') return json(ensureRows_(d));
        // App mở Lịch sử quay → quét video của tháng đó trên Drive, tạo lại dòng bị mất
        if (d.action === 'syncFromDrive') {
          ensureTrigger_();
          return json({ ok: true, version: SCRIPT_VERSION, created: syncFromDrive_(d.sheet) });
        }

        // Xoá 1 dòng theo ID (app gọi khi xoá trong LỊCH SỬ QUAY)
        if (d.action === 'delete') {
          const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(String(d.sheet || DEFAULT_TAB));
          if (!sh || !d.id) return json({ ok: true, deleted: false });
          migrate_(sh);
          const r = findRowById_(sh, d.id);
          if (!r) return json({ ok: true, deleted: false });
          // Dòng có video CÒN trên Drive → giữ nguyên (thông tin đi theo video, muốn bỏ thì xoá video trên Drive trước)
          const linked = linkedFileId_(sh.getName(), d.id, '');
          if (linked) {
            let alive = false;
            try { alive = !DriveApp.getFileById(linked).isTrashed(); } catch (e) {}
            if (alive) return json({ ok: true, deleted: false, keptForDrive: true });
          }
          // Video đã lên Drive: KHÔNG đụng tới file trên Drive (chỉ xoá trên Drive bằng tay mới mất)
          // Máy cuối cùng của ngày đó → xoá luôn dòng tách ngày phía trên
          const seps = sepRows_(sh);
          const aboveIsSep = seps[r - 1] === true;
          const belowEndsDay = (r + 1 > sh.getLastRow()) || seps[r + 1] === true;
          sh.deleteRow(r);
          if (aboveIsSep && belowEndsDay) sh.deleteRow(r - 1);
          try { compact_(sh); tidy_(sh); } catch (e) { /* dọn lỗi không ảnh hưởng việc xoá */ }
          return json({ ok: true, deleted: true });
        }

        const t = getTab_(d.sheet);
        // Dọn dòng trống / ngày rỗng trước khi ghi → máy mới không bị ghi lọt vào ngày rỗng cũ
        try { compact_(t.sh); } catch (e) {}
        // App gửi lại khi mất mạng → bỏ qua nếu ID đã có
        if (d.id && findRowById_(t.sh, d.id)) return json({ ok: true, duplicate: true });

        appendRow_(t, d);
        return json({ ok: true });
      } catch (err) {
        return json({ ok: false, error: String(err) });
      } finally {
        lock.releaseLock();
      }
    }

    /** App đọc: ?action=tabs | ?sheet=<tab>&limit=200&q=<mã|khách|NV>. Mới nhất trước. */
    function doGet(e) {
      const p = (e && e.parameter) || {};
      const ss = SpreadsheetApp.getActiveSpreadsheet();
      if (p.action === 'tabs') {
        return json({ ok: true, version: SCRIPT_VERSION, tabs: ss.getSheets().map(s => s.getName()) });
      }
      // Dọn tất cả tab có cấu trúc bảng (tô tiêu đề, bỏ cột thừa, canh cột)
      if (p.action === 'tidy') {
        const done = [];
        ss.getSheets().forEach(s => {
          if (header_(s).indexOf(norm_('Mã máy')) >= 0) { migrate_(s); compact_(s); tidy_(s); done.push(s.getName()); }
        });
        return json({ ok: true, version: SCRIPT_VERSION, tidied: done });
      }
      const limit = Math.min(parseInt(p.limit || '200', 10) || 200, 1000);
      const q = String(p.q || '').toUpperCase().trim();
      const tab = String(p.sheet || DEFAULT_TAB);
      const sh = ss.getSheetByName(tab);
      if (!sh || sh.getLastRow() < 2) return json({ ok: true, version: SCRIPT_VERSION, sheet: tab, total: 0, rows: [] });

      const lock = LockService.getScriptLock();
      lock.waitLock(20000);
      try { migrate_(sh); } finally { lock.releaseLock(); }

      const header = header_(sh);
      const idx = {};
      COLUMNS.forEach(([title, key]) => { idx[key] = header.indexOf(norm_(title)); });
      const meta = rowMeta_(sh);
      const seps = sepRows_(sh);

      const n = sh.getLastRow() - 1;
      const vals = sh.getRange(2, 1, n, header.length).getDisplayValues();
      // Link video Drive ở ô Mã máy → app biết dòng nào đã có video trên Drive
      const rich = idx.code >= 0 ? sh.getRange(2, idx.code + 1, n, 1).getRichTextValues() : [];
      const driveOf = k => {
        const v = rich[k] && rich[k][0];
        if (!v) return '';
        const m = [v.getLinkUrl()].concat(v.getRuns().map(x => x.getLinkUrl())).join(' ').match(/\/d\/([\w-]{20,})/);
        return m ? m[1] : '';
      };
      let rows = vals
        .map((r, k) => {
          if (seps[k + 2]) return null;   // dòng tách ngày → không phải lần quay

          const o = {};
          COLUMNS.forEach(([, key]) => { o[key] = idx[key] >= 0 ? r[idx[key]] : ''; });
          const m = meta[k + 2] || {};
          o.id = m.id || '';
          o.file = m.file || '';
          o.drive = driveOf(k);
          return o;
        })
        .filter(o => o && (o.code || o.customer || o.date)) // bỏ dòng trống + dòng tách ngày
        .reverse();
      if (q) rows = rows.filter(r => (r.code + ' ' + r.customer + ' ' + r.staff + ' ' + r.type)
        .toUpperCase().indexOf(q) >= 0);
      return json({ ok: true, version: SCRIPT_VERSION, sheet: tab, total: rows.length, rows: rows.slice(0, limit) });
    }

    function json(o) {
      return ContentService.createTextOutput(JSON.stringify(o))
        .setMimeType(ContentService.MimeType.JSON);
    }
