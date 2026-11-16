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
    const SCRIPT_VERSION = 14; // ...12: Drive, 13: tab tháng mới không mất dòng đầu, 14: xoá từ app không xoá video Drive
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
  // Video lưu ở Drive của chủ script: "QuayTest Video/<tab>/<tên file>.mp4"
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
  function driveUploadInit_(d) {
    const tab = String(d.sheet || DEFAULT_TAB);
    const folder = folder_(folder_(null, DRIVE_ROOT), tab);
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
    return { ok: true, uploadUrl: url };
  }

  /** Tải xong: gắn link video vào ô "Mã máy" của dòng có ID tương ứng. */
  function driveUploadDone_(d) {
    const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(String(d.sheet || DEFAULT_TAB));
    if (!sh || !d.id || !d.fileId) return { ok: true, linked: false };
    const r = findRowById_(sh, d.id);
    if (!r) return { ok: true, linked: false };
    const col = header_(sh).indexOf(norm_('Mã máy')) + 1;
    if (col < 1) return { ok: true, linked: false };
    const cell = sh.getRange(r, col);
    const text = cell.getDisplayValue() || String(d.code || '');
    const url = 'https://drive.google.com/file/d/' + d.fileId + '/view';
    cell.setRichTextValue(SpreadsheetApp.newRichTextValue().setText(text).setLinkUrl(url).build());
    return { ok: true, linked: true, url: url };
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

    function doPost(e) {
      const lock = LockService.getScriptLock();
      lock.waitLock(20000);
      try {
        const d = JSON.parse(e.postData.contents);

        // ===== Google Drive: xin link tải lên 1 lần cho 1 video =====
        if (d.action === 'uploadInit') return json(driveUploadInit_(d));
        // ===== Google Drive: tải xong → gắn link video vào ô Mã máy =====
        if (d.action === 'uploadDone') return json(driveUploadDone_(d));

        // Xoá 1 dòng theo ID (app gọi khi xoá trong LỊCH SỬ QUAY)
        if (d.action === 'delete') {
          const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(String(d.sheet || DEFAULT_TAB));
          if (!sh || !d.id) return json({ ok: true, deleted: false });
          migrate_(sh);
          const r = findRowById_(sh, d.id);
          if (!r) return json({ ok: true, deleted: false });
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

        const row = new Array(t.width).fill('');
        COLUMNS.forEach(([, key, kind]) => {
          let v = d[key];
          if (v === undefined || v === null) v = '';
          if (kind === 'date') v = toDate_(v);
          else if (kind === 'code' && v !== '') v = "'" + v; // giữ số 0 đầu, không tự đổi kiểu
          row[t.pos[key] - 1] = v;
        });
        // Ghi vào dòng trống đầu tiên sau dữ liệu (nằm trong bảng nếu bảng còn dòng trống)
        let r = t.sh.getLastRow() + 1;
        // Ngày mới (khác ngày của dòng cuối) → chèn dòng tách ngày trước
        const day = String(d.date || '');
        if (day && lastDay_(t.sh, t.pos.date) !== day) {
          writeSep_(t.sh, r, day, t.pos, t.width);
          r += 1;
        }
        t.sh.getRange(r, 1, 1, row.length).setValues([row])
          .setBackground(null).setFontColor(null).setFontWeight('normal').setBorder(false, false, false, false, false, false);
        const rowRange = t.sh.getRange(r + ':' + r);
        if (d.id) rowRange.addDeveloperMetadata(MD_ID, String(d.id));
        if (d.file) rowRange.addDeveloperMetadata(MD_FILE, String(d.file));
        try { tidy_(t.sh); } catch (e) { /* canh cột lỗi không ảnh hưởng việc ghi */ }
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
      let rows = vals
        .map((r, k) => {
          if (seps[k + 2]) return null;   // dòng tách ngày → không phải lần quay

          const o = {};
          COLUMNS.forEach(([, key]) => { o[key] = idx[key] >= 0 ? r[idx[key]] : ''; });
          const m = meta[k + 2] || {};
          o.id = m.id || '';
          o.file = m.file || '';
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
