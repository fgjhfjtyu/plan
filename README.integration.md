# QET Mobile — merged delivery

این نسخه سه منبع ارسالی را در یک پروژه ادغام می‌کند:

1. اسکلت کامل Android و Java bridge از `plan-main`.
2. سورس QET Mobile / مراحل 1 تا 5 به‌عنوان پایهٔ ویرایشگر.
3. رابط و کاتالوگ ۹۰۰۰ قطعهٔ صنعتی از نسخهٔ ۹۰۰۰تایی.

فایل `app/src/main/assets/index.html` نسخهٔ نهایی WebView است و فایل‌های `app/src/main/assets/parts/cat_01.js` تا `cat_12.js` داده‌های ۹۰۰۰ قطعه را نگه می‌دارند.

برای اعتبارسنجی:

```bash
python3 validate_parts.py
```

برای ساخت APK:

```bash
gradle assembleDebug --no-daemon
```

یا workflow موجود در `.github/workflows/build-apk.yml` را در GitHub Actions اجرا کنید.
