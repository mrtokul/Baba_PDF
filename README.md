# Baba PDF — Android

Masaüstü Baba PDF'in Android sürümü (Kotlin + Jetpack Compose + PDFBox-Android; sayfa çizimi Android `PdfRenderer`). Belgeler telefondan çıkmaz; uygulama ağ izni istemez.

## Özellikler (ilk sürüm)
PDF görüntüleme (kaydırma, yakınlaştırma, sayfaya git, koyu okuma), metin arama, birleştirme, sayfa çıkarma/bölme, sayfa silme, döndürme, küçültme, şifreli kopya, şifreli PDF açma, metni .txt olarak, sayfayı PNG olarak dışa aktarma, geri alma (5 adım). Özgün dosya değişmez; "Kaydet" ile yeni dosya yazılır.

Masaüstündeki OCR, imza, karartma, açıklamalar, EPUB/MOBI/RTF okuma bu sürümde yoktur.

## APK'yı GitHub'da derleme (bilgisayarda kurulum gerekmez)
1. GitHub'da yeni bir depo açın ve bu klasörün **içeriğini** depo köküne yükleyin (`.github` klasörü dahil).
2. Depoda **Actions** sekmesi → **APK derle** → **Run workflow** (ya da `main`'e her gönderimde kendiliğinden çalışır).
3. İş bitince çalıştırma sayfasının altındaki **BabaPDF-apk** dosyasını indirip açın; içindeki `app-debug.apk` telefona kurulur (ayarlardan "bilinmeyen kaynaklardan kuruluma" izin verin).

## Android Studio ile
Klasörü Android Studio'da açın → Build ▸ Build APK(s). Çıktı: `app/build/outputs/apk/debug/app-debug.apk`.

## Lisans
Masaüstü sürümle aynı: AGPL-3.0 (kök `LICENSE.txt`'yi bu klasöre kopyalayın).
