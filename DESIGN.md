# DESIGN.md — Antarmuka Kamera

## 1. Tujuan dan sumber

Dokumen ini menerjemahkan dua halaman referensi visual `Page 1.pdf` serta klarifikasi pemilik desain menjadi panduan antarmuka kamera potret. **Halaman 1 adalah mode pengambilan 4:3; halaman 2 adalah mode fullscreen/16:9.** Susunan kontrol utamanya sama, tetapi bingkai pratinjau dan permukaan kontrol mengikuti mode yang dipilih. Kemampuan kamera, pemrosesan gambar, format berkas, serta batasan perangkat mengikuti PRD dan SPEC proyek bila tersedia.

**Status spesifikasi:** fungsi kontrol, font, dan arti kedua halaman berasal dari klarifikasi pemilik desain. Posisi, hierarki, dan rupa kontrol berasal dari PDF. Ukuran numerik dan detail interaksi popup yang tidak diperlihatkan adalah rekomendasi implementasi.

## 2. Karakter visual

- Antarmuka minimal, berorientasi pada pratinjau foto. Tidak ada judul, navigasi tab, atau panel informasi yang memenuhi layar.
- Dasar warna hitam, kontrol putih, dan aksen oranye terang pada flash, EV, serta nilai EV aktif.
- Bentuk kontrol utama berupa lingkaran dan kapsul; tombol rana sangat dominan.
- Seluruh teks dan angka antarmuka memakai **Space Mono** (misalnya label rasio, label lensa `1X`, label dan skala EV, serta teks dalam popup). Ikon tetap berupa aset ikon, bukan karakter font.
- Contoh foto pada referensi hanya konten pratinjau. Orang, pakaian, lokasi, dan komposisi fotonya bukan bagian dari ketentuan UI.
- Referensi ditampilkan pada layar potret yang sangat memanjang, mendekati rasio perangkat 9:19,5. Rasio hasil foto dan rasio layar perangkat adalah dua hal berbeda.

## 3. Mode rasio dan dua halaman referensi

| Aspek | Halaman 1 — pengambilan 4:3 | Halaman 2 — fullscreen/16:9 |
| --- | --- | --- |
| Mode terpilih | `4:3` | `16:9` / fullscreen |
| Area atas | Bilah hitam solid di atas pratinjau | Kapsul hitam tembus pandang di atas pratinjau |
| Pratinjau | Bidang foto 3:4 dalam orientasi potret; ada batas atas dan bawah yang jelas | Pratinjau memenuhi layar di belakang kontrol |
| Kontrol bawah | Latar hitam solid | Kontrol melayang di atas gambar; sebagian memakai permukaan gelap tembus pandang |
| Peralihan | Memilih rasio lain dari popup rasio | Memilih `4:3` dari popup rasio |

Kedua halaman adalah **keadaan dari satu layar kamera**, bukan pilihan tema yang terpisah. Rasio foto dipilih melalui popup yang menempel pada kontrol rasio. Pada gambar halaman 2, label bagian atas masih terbaca `4:3`; klarifikasi pemilik desain menetapkan halaman tersebut sebagai fullscreen/16:9, sehingga **label pada implementasi harus menampilkan rasio yang benar-benar aktif**. Jangan memakai `4:3` untuk foto yang disimpan sebagai 16:9.

## 4. Anatomi layar

Urutan dari atas ke bawah:

1. **Kontrol atas:** tombol flash di kiri, tombol rasio (misalnya `4:3`) di tengah, dan ikon pengaturan berbentuk roda gigi di kanan. Flash dan rasio membuka popup yang menempel pada tombol masing-masing.
2. **Pratinjau kamera:** bidang visual utama. Referensi memakai foto kolase sebagai contoh visual; implementasi kamera menggunakan live preview dengan framing sesuai mode rasio yang aktif.
3. **Pemilih style gambar:** tombol kapsul berbingkai putih di sisi kiri, dengan segmen merah, kuning, hijau, dan biru. Tombol ini membuka popup style gambar yang menempel padanya.
4. **Pemilih lensa:** tombol lingkaran berbingkai putih berlabel `1X` pada keadaan yang terlihat. Tombol ini membuka popup pilihan lensa yang menempel padanya; `1X` adalah lensa/pilihan aktif yang ditampilkan, bukan tombol zoom sekali tekan.
5. **Slider kompensasi eksposur:** berada **di atas tombol shutter**. Label `EV` ada di sisi kiri; skala mendatar memiliki tanda kecil serta nilai seperti `-1`, `0`, `1`, dan `+0`. Nilai aktif `0` berwarna oranye dengan titik putih di atasnya. Pada mode fullscreen, slider menggunakan kapsul gelap tembus pandang.
6. **Kontrol pengambilan:** thumbnail foto terakhir di kiri, tombol rana besar di tengah, dan tombol ganti kamera di kanan.

## 5. Komponen dan perilaku

| Komponen | Tampilan | Perilaku |
| --- | --- | --- |
| Flash | Simbol petir oranye di kiri atas | Ketuk untuk membuka **anchored popup** berisi mode flash yang didukung. Tampilkan pilihan aktif pada tombol/popup. |
| Rasio foto | Label Space Mono di tengah atas | Ketuk untuk membuka **anchored popup** pilihan rasio; memilih `4:3` atau `16:9` memperbarui pratinjau dan label sesuai mode aktif. |
| Pengaturan | Roda gigi putih | Membuka pengaturan kamera. |
| Style gambar | Kapsul putih dengan segmen warna, di kiri tombol lensa | Ketuk untuk membuka **anchored popup** pilihan style gambar. Tunjukkan style aktif dan terapkan hasilnya sesuai definisi produk. |
| Lensa | Lingkaran putih berlabel `1X` | Ketuk untuk membuka **anchored popup** pilihan lensa. Daftar hanya memuat lensa yang tersedia; label tombol mengikuti pilihan aktif. |
| EV | `EV` oranye, skala putih, nilai aktif oranye, titik putih | Slider horizontal tepat di atas shutter. Geser untuk mengubah kompensasi eksposur dalam rentang perangkat dan tampilkan nilai aktual. |
| Thumbnail | Pratinjau persegi kecil bersudut membulat | Buka foto terbaru atau galeri yang dipilih produk. Bila belum ada foto, gunakan placeholder netral. |
| Rana | Pusat putih besar, cincin hitam, lingkar luar putih | Ketuk untuk mengambil foto. Berikan umpan balik singkat ketika pengambilan sedang diproses. |
| Ganti kamera | Ikon kamera dan panah melingkar putih pada latar gelap bulat | Beralih kamera depan/belakang jika tersedia. |

**Pola anchored popup:** popup muncul dekat tombol yang membukanya dan menunjuk/terkait jelas dengan tombol tersebut. Hanya satu popup terbuka pada satu waktu. Memilih item menutup popup dan memperbarui kontrol; ketukan di luar atau tombol Back menutupnya tanpa perubahan. Posisi popup menyesuaikan tepi layar, notch, dan area gestur. Isi rinci pilihan flash, rasio tambahan, daftar lensa, dan style perlu mengikuti PRD serta kemampuan perangkat; PDF tidak menampilkan keadaan popup terbuka.

## 6. Tata letak dan responsivitas

- Gunakan koordinat relatif terhadap area layar yang aman, bukan posisi piksel tetap dari PDF. Pertahankan urutan dan kesejajaran tiga kontrol atas serta tiga kontrol pengambilan.
- **Mode 4:3:** area atas sekitar 9% tinggi layar; pratinjau foto sekitar 61%; area kontrol bawah sekitar 30%. Ini perkiraan komposisi halaman 1. Pertahankan bingkai hasil 3:4 pada orientasi potret tanpa meregangkan gambar.
- **Mode fullscreen/16:9:** pratinjau meluas ke belakang kontrol; kapsul atas memiliki margin kiri dan kanan; area bawah berada di atas gambar dengan ruang aman dari tepi bawah. Jangan letakkan kontrol di bawah status bar, notch, atau bilah gestur.
- Baris pemilih style dan lensa berada di atas slider EV. Slider EV berada di atas baris thumbnail, shutter, dan tombol ganti kamera pada kedua mode.
- Tempatkan rana pada sumbu tengah layar. Thumbnail dan tombol ganti kamera mengapitnya dengan jarak visual yang seimbang.
- Teks dan ikon pada gambar harus tetap terbaca pada latar terang maupun gelap. Gunakan permukaan hitam tembus pandang, bayangan halus, atau scrim seperlunya, terutama pada mode fullscreen/16:9.
- Pada mode 16:9, layar perangkat yang lebih panjang dari 16:9 mungkin memerlukan crop untuk preview penuh. Tunjukkan batas framing foto secara jujur sesuai SPEC agar bagian yang tampak di luar hasil tidak menyesatkan pengguna.
- Sediakan target sentuh yang memadai, sekitar **48 dp** minimum untuk kontrol kecil. Skala EV boleh lebih lebar; thumbnail dan shutter tetap mudah dijangkau satu tangan.

## 7. Token visual awal

Nilai warna dan ukuran berikut adalah pendekatan untuk memulai implementasi. **Space Mono adalah keputusan desain**, bukan perkiraan. Cocokkan angka lain dengan aset desain asli jika tersedia.

| Token | Nilai awal | Pemakaian |
| --- | --- | --- |
| `color.background` | `#000000` | Panel atas dan bawah pada halaman 1 |
| `color.foreground` | `#FFFFFF` | Ikon, teks, bingkai, rana |
| `color.accent` | `#FF5A24` | Flash, EV, nilai EV aktif |
| `color.controlSurface` | `#1E1E1E` | Tombol bulat gelap |
| `color.overlaySurface` | `rgba(0, 0, 0, 0.58)` | Kapsul kontrol di atas pratinjau |
| `shape.control` | Lingkaran/kapsul penuh | Tombol, indikator, bilah overlay |
| `shape.thumbnail` | Radius sekitar 12 dp | Foto terakhir |
| `type.family` | `Space Mono` | Seluruh teks dan angka UI, termasuk popup |
| `type.weight.normal` | 400 | Label rasio, lensa, angka skala |
| `type.weight.emphasis` | 700 | Label aktif jika diperlukan untuk hierarki |

Perkiraan ukuran pada lebar perangkat sekitar 390 dp: margin horizontal 16–24 dp; ikon atas 24–28 dp dalam target sentuh 48 dp; tombol lensa sekitar 36–44 dp; thumbnail sekitar 56–64 dp; tombol ganti kamera sekitar 60–68 dp; rana sekitar 96–112 dp termasuk cincin luar. Sesuaikan agar proporsi referensi tetap terasa seimbang pada berbagai ukuran layar.

## 8. Keadaan yang perlu dirancang

- **Siap memotret 4:3:** preview dalam bidang 3:4, panel hitam, label `4:3`, lensa `1X`, dan EV `0`.
- **Siap memotret 16:9:** preview fullscreen dengan kontrol overlay; label rasio menunjukkan `16:9`, sementara lensa, style, flash, dan EV mempertahankan pilihan aktifnya.
- **Popup terbuka:** flash, rasio, lensa, atau style muncul dari tombol pemicunya; pilihan aktif terlihat jelas dan popup lain tertutup.
- **Mengubah EV:** nilai aktif serta titik indikator bergerak bersama; skala tetap terlihat dan dapat dikendalikan dengan presisi.
- **Mengambil foto:** rana memberi respons, lalu thumbnail diperbarui setelah berkas berhasil disimpan.
- **Kontrol tidak tersedia:** bila kamera/perangkat tidak mendukung flash, lensa tertentu, rasio, style, atau rentang EV, tampilkan status nonaktif yang jelas dan ikuti fallback pada SPEC.
- **Izin kamera belum diberikan / preview gagal:** tampilkan pesan dan tindakan pemulihan tanpa menyisakan preview kosong yang menyerupai kamera aktif.
- **Belum ada foto terakhir:** thumbnail diganti placeholder yang konsisten dengan visual gelap.

## 9. Aksesibilitas

- Setiap ikon memiliki label aksesibilitas dalam bahasa aplikasi, misalnya “Pilih flash”, “Pengaturan”, “Pilih rasio foto”, “Pilih lensa”, “Pilih style gambar”, “Ambil foto”, dan “Ganti kamera”.
- Popup dapat ditelusuri dengan pembaca layar, memiliki fokus awal yang jelas, dan mengembalikan fokus ke tombol pemicunya ketika ditutup.
- Nilai EV serta pilihan flash, rasio, lensa, dan style dibacakan bersama statusnya. Jangan mengandalkan warna oranye sebagai satu-satunya penanda pilihan.
- Pertahankan kontras teks dan ikon di atas live preview yang berubah-ubah. Target sentuh kontrol kecil tetap cukup besar meskipun ikon terlihat mungil.
- Tata letak tetap dapat dipakai pada ukuran font sistem yang lebih besar; jangan memotong teks angka/label kontrol.

## 10. Batas interpretasi

Klarifikasi pemilik desain telah menetapkan **Space Mono**, fungsi tombol style, popup flash/rasio/lensa/style, slider EV, dan arti kedua halaman. PDF belum menetapkan kode warna resmi, dimensi dalam dp, animasi, isi dan bentuk pasti setiap popup, daftar pilihan lensa/style/flash tambahan, atau alur galeri. Detail yang disarankan perlu diselaraskan dengan PRD dan kemampuan perangkat. Bila proyek ini adalah aplikasi kamera RAW-first, keputusan tentang RAW, HDR, fusion, metadata, dan pipeline pengambilan tetap berasal dari PRD/SPEC, bukan dari mockup UI ini.
