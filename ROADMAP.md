# Baba PDF (Android) — Değerlendirme ve Yol Haritası

Bu dosya, EXE sürümünden APK'ya taşıma sonrası yapılan eleştirel değerlendirmeyi ve önerilen yeni özellikleri içerir.
Durum sütunu: ✅ yapıldı · 🟡 kısmen · ⬜ sırada.

## 1. Eleştirel değerlendirme

| # | Eleştiri | Durum |
|---|----------|-------|
| 1 | Hiçbir özellik çalışırken denenmedi, yalnızca derlendi | ✅ Emülatörde otomatik sınamalar eklendi (`app/src/androidTest`, `.github/workflows/test.yml`). Sayfa, arama, şifre, karartma, damga, kırpma, yer imi, OCR, imza, RTF/IBA/MOBI sınanıyor |
| 2 | Uzun işlemlerde ilerleme/iptal yok | ✅ İlerleme çubuğu + İptal düğmesi (OCR, raster küçültme, PNG/görsel ZIP, resimden PDF) |
| 3 | Arayüz masaüstü menüsü kopyası, tek uzun menü | 🟡 Menüye komut arama kutusu ve sayfa ızgarası eklendi; menü yeniden gruplanmadı |
| 4 | Sayfaları metin diyaloğuyla seçmek telefonda zor | ✅ Sayfa küçük resim ızgarası: seç, sil, döndür, taşı, ayrı PDF |
| 5 | Debug APK, simge yok | 🟡 Simge eklendi. Yayın imzası ve `.aab` yok (anahtar gizli tutulmalı, bkz. 3.D) |
| 6 | Her işlem tüm dosyayı kopyalıyor, geri al 8 adım | ⬜ Büyük dosya testi ve bellek sınırı (3.A) |
| 7 | OCR dönük sayfaları atlıyor; AZW3 (KF8) düzeni bozulabilir | ⬜ Bilinen sınır |
| 8 | Tek dosyalık `MainActivity` (1000+ satır) | ⬜ Ekranlara/diyaloglara bölünmeli |

## 2. Bu turda eklenenler
- Sayfa ızgarası (küçük resimler, çoklu seçim)
- Kamerayla sayfa tarama, resimlerden PDF yapma/ekleme (EXIF yönü uygulanır)
- Form doldurma (metin, onay kutusu, radyo, seçim listesi)
- Paylaş menüsünden PDF/resim alma
- İlerleme çubuğu ve iptal
- Uygulama simgesi
- Emülatörde otomatik sınama hattı

## 3. Önerilen yeni özellikler (öncelik sırasıyla)

### A. Güvenilirlik
1. Büyük dosya (100 MB+) sınaması; kopyasız çalışma ve geri al yığınında bellek sınırı.
2. Sınama kapsamını genişletme: form doldurma, otomatik döndürme, açıklama ekleme, kıyaslama.
3. Kilitlenme raporu (yalnızca cihazda, isteğe bağlı paylaşım).

### B. Telefona uygun kullanım
4. Metin seçme, kopyalama, seçimden vurgu/not.
5. Parmakla çizim ve el yazısı imza (kaydedilmiş imza resmi).
6. Çift dokunarak yakınlaştırma, sayfa atlama kaydırıcısı.
7. Sayfa ızgarasında sürükle-bırak sıralama (şimdi yalnızca "öne/sona" düğmesi).

### C. İş akışı
8. Tarama iyileştirme: kenar bulma, perspektif düzeltme, siyah-beyaz, otomatik OCR.
9. Toplu işlem: birden çok dosyaya aynı küçültme/filigran.
10. Son okunan sayfayı hatırlama.
11. Bulut (Drive vb.) kaydetme — Android belge seçicisiyle kısmen çalışır, sınanmalı.

### D. Dağıtım
12. Yayın imzası: anahtar deposu GitHub Secrets'ta tutulur, `assembleRelease` + `bundleRelease`.
13. Sürüm numarası otomasyonu, Play Store bilgileri, gizlilik metni (tüm işlem cihazda).
14. Ayarlar ekranı: tema, varsayılan OCR dili, e-kitap yazı boyutu.

## 4. Bilinen sınırlar
- Dönük sayfalarda OCR atlanır; sayfa boyutu/kırpma/damga dönük sayfalarda kayabilir.
- AZW3 (KF8) kitaplarda düzen bozulabilir; DRM'li ve HUFF/CDIC sıkıştırmalı Kindle dosyaları açılmaz.
- Sertifika güveni Android sistem kök sertifikalarıyla denetlenir; kamu e-imza zincirleri tanınmayabilir.
- Görsel ayıklama yalnızca sayfaya doğrudan bağlı resimleri bulur.
