-- 음소거·최소 중요도 미만 알림도 앱 목록에는 남긴다 (docs/00 7.5).
-- display: NORMAL(알림), QUIET(소리 없는 알림), INBOX(알림 없이 목록만)
ALTER TABLE deliveries ADD COLUMN display TEXT NOT NULL DEFAULT 'NORMAL' CHECK (display IN ('NORMAL', 'QUIET', 'INBOX'));
ALTER TABLE deliveries ADD COLUMN reason TEXT;
UPDATE deliveries SET display = 'QUIET' WHERE quiet = 1
