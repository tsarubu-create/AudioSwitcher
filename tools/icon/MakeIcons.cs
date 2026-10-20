#:property TargetFramework=net10.0-windows
#:property UseWindowsForms=true
#:property PublishAot=false

// AudioSwitcher のアイコンを作る。意匠（画面と音の波）はここだけに書き、PC の .ico・Android のベクター・確認用の SVG を同じ図形から書き出す。
// 使い方（リポジトリの根から）: dotnet run tools/icon/MakeIcons.cs
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Globalization;
using System.Text;

var root = args.Length > 0 ? args[0] : Directory.GetCurrentDirectory();
var pcIcons = Path.Combine(root, "pc", "src", "AudioSwitcher.Tray", "Icons");
var res = Path.Combine(root, "android", "app", "src", "main", "res");
Directory.CreateDirectory(pcIcons);

// 送信中と exe は青、待ち受け中は灰色。どちらも白い絵を載せる。
WriteIco(Path.Combine(pcIcons, "app.ico"), Design.Blue);
WriteIco(Path.Combine(pcIcons, "idle.ico"), Design.Gray);
File.WriteAllText(Path.Combine(root, "tools", "icon", "icon.svg"), Design.Svg(Design.Blue));

// Android: アダプティブ アイコン（背景の色・前景の絵・単色の絵）と、通知の小さなアイコン。
// 前景は 108dp の画布の真ん中 66dp（安全な範囲）に収める。24 の格子を 2.75 倍して真ん中に置く。
WriteText(Path.Combine(res, "drawable", "ic_launcher_foreground.xml"), Design.AndroidVector(108, 2.75, 54 - 12 * 2.75));
WriteText(Path.Combine(res, "drawable", "ic_notification.xml"), Design.AndroidVector(24, 1, 0));
WriteText(Path.Combine(res, "values", "ic_launcher_background.xml"), $"""
    <?xml version="1.0" encoding="utf-8"?>
    <!-- tools/icon/MakeIcons.cs が書き出す。手で直さない。 -->
    <resources>
        <color name="ic_launcher_background">{Design.Hex(Design.Blue)}</color>
    </resources>

    """);
WriteText(Path.Combine(res, "mipmap-anydpi", "ic_launcher.xml"), """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- tools/icon/MakeIcons.cs が書き出す。手で直さない。 -->
    <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        <background android:drawable="@color/ic_launcher_background" />
        <foreground android:drawable="@drawable/ic_launcher_foreground" />
        <monochrome android:drawable="@drawable/ic_launcher_foreground" />
    </adaptive-icon>

    """);
Console.WriteLine("書き出した: " + pcIcons + " と " + res);

// 2つ目の引数にフォルダを渡すと、小さい寸法の見え方を確かめる拡大画像（点を 8 倍）を置く。
if (args.Length > 1)
{
    Directory.CreateDirectory(args[1]);
    foreach (var (name, color) in new[] { ("app", Design.Blue), ("idle", Design.Gray) })
    {
        foreach (var size in new[] { 16, 20, 24, 32, 48 })
        {
            using var small = Design.Render(size, color);
            using var big = new Bitmap(size * 8, size * 8);
            using (var g = Graphics.FromImage(big))
            {
                g.InterpolationMode = InterpolationMode.NearestNeighbor;
                g.PixelOffsetMode = PixelOffsetMode.Half;
                g.DrawImage(small, 0, 0, size * 8, size * 8);
            }

            big.Save(Path.Combine(args[1], $"{name}-{size}.png"), ImageFormat.Png);
        }
    }
}

static void WriteText(string path, string text)
{
    Directory.CreateDirectory(Path.GetDirectoryName(path)!);
    File.WriteAllText(path, text.ReplaceLineEndings("\n"), new UTF8Encoding(false));
}

// 48px 以下は DIB（BMP）、256px は PNG で入れる。System.Drawing.Icon が小さい PNG の枠を読めないことがあるため。
static void WriteIco(string path, Color tile)
{
    int[] sizes = [16, 20, 24, 32, 40, 48, 64, 256];
    var images = sizes.Select(s => s >= 256 ? Png(Design.Render(s, tile)) : Dib(Design.Render(s, tile))).ToArray();
    using var output = new BinaryWriter(File.Create(path));
    output.Write((short)0);
    output.Write((short)1);
    output.Write((short)sizes.Length);
    var offset = 6 + 16 * sizes.Length;
    for (var i = 0; i < sizes.Length; i++)
    {
        output.Write((byte)(sizes[i] >= 256 ? 0 : sizes[i]));
        output.Write((byte)(sizes[i] >= 256 ? 0 : sizes[i]));
        output.Write((byte)0);
        output.Write((byte)0);
        output.Write((short)1);
        output.Write((short)32);
        output.Write(images[i].Length);
        output.Write(offset);
        offset += images[i].Length;
    }

    foreach (var image in images)
    {
        output.Write(image);
    }
}

static byte[] Png(Bitmap bitmap)
{
    using var stream = new MemoryStream();
    bitmap.Save(stream, ImageFormat.Png);
    bitmap.Dispose();
    return stream.ToArray();
}

static byte[] Dib(Bitmap bitmap)
{
    var size = bitmap.Width;
    using var stream = new MemoryStream();
    using var w = new BinaryWriter(stream);
    w.Write(40);
    w.Write(size);
    w.Write(size * 2); // ICO の決まりで、色と透過のマスクを合わせた高さを書く。
    w.Write((short)1);
    w.Write((short)32);
    w.Write(0);
    w.Write(0);
    w.Write(0);
    w.Write(0);
    w.Write(0);
    w.Write(0);
    for (var y = size - 1; y >= 0; y--)
    {
        for (var x = 0; x < size; x++)
        {
            var c = bitmap.GetPixel(x, y);
            w.Write(c.B);
            w.Write(c.G);
            w.Write(c.R);
            w.Write(c.A);
        }
    }

    // 透過のマスク（1bit・行は 4 バイト単位）。透明な点に 1 を立てる。
    var rowBytes = (size + 31) / 32 * 4;
    for (var y = size - 1; y >= 0; y--)
    {
        var row = new byte[rowBytes];
        for (var x = 0; x < size; x++)
        {
            if (bitmap.GetPixel(x, y).A == 0)
            {
                row[x / 8] |= (byte)(0x80 >> (x % 8));
            }
        }

        w.Write(row);
    }

    bitmap.Dispose();
    return stream.ToArray();
}

/// <summary>意匠。24×24 の格子で、角丸の地に白い画面（台つき）と、右へ広がる2本の音の波。</summary>
static class Design
{
    public static readonly Color Blue = Color.FromArgb(0x1E, 0x6F, 0xD9);
    public static readonly Color Gray = Color.FromArgb(0x6B, 0x77, 0x85);

    // 地の角丸の四角（x, y, 幅, 高さ, 角の半径）。
    static readonly (float X, float Y, float W, float H, float R) Tile = (0f, 0f, 24f, 24f, 4.5f);

    // 白く塗る角丸の四角: 画面・首・台。16px で 1 点に当たる 1.5 の倍数に寄せて、小さくてもぼやけにくくする。
    static readonly (float X, float Y, float W, float H, float R)[] Fills =
    [
        (3f, 6f, 12f, 9f, 1.5f),
        (7.5f, 14.5f, 3f, 2.5f, 0f),
        (4.5f, 16.5f, 9f, 1.5f, 0.75f),
    ];

    // 白い線で描く音の波（中心 x, 中心 y, 半径, 線の太さ）。右へ ±50 度の弧で、端は丸める。
    static readonly (float X, float Y, float R, float Width)[] Waves =
    [
        (15f, 10.5f, 3.75f, 2.25f),
        (15f, 10.5f, 6.75f, 2.25f),
    ];

    const float WaveHalfAngle = 50f;

    public static Bitmap Render(int size, Color tile)
    {
        var bitmap = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bitmap);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.Clear(Color.Transparent);
        g.ScaleTransform(size / 24f, size / 24f);
        using (var path = RoundRect(Tile))
        using (var brush = new SolidBrush(tile))
        {
            g.FillPath(brush, path);
        }

        foreach (var fill in Fills)
        {
            using var path = RoundRect(fill);
            g.FillPath(Brushes.White, path);
        }

        foreach (var wave in Waves)
        {
            using var pen = new Pen(Color.White, wave.Width) { StartCap = LineCap.Round, EndCap = LineCap.Round };
            g.DrawArc(pen, wave.X - wave.R, wave.Y - wave.R, wave.R * 2, wave.R * 2, -WaveHalfAngle, WaveHalfAngle * 2);
        }

        return bitmap;
    }

    static GraphicsPath RoundRect((float X, float Y, float W, float H, float R) r)
    {
        var path = new GraphicsPath();
        if (r.R <= 0)
        {
            path.AddRectangle(new RectangleF(r.X, r.Y, r.W, r.H));
            return path;
        }

        var d = r.R * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.X + r.W - d, r.Y, d, d, 270, 90);
        path.AddArc(r.X + r.W - d, r.Y + r.H - d, d, d, 0, 90);
        path.AddArc(r.X, r.Y + r.H - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    public static string Hex(Color c) => $"#{c.R:X2}{c.G:X2}{c.B:X2}";

    static string F(float v) => v.ToString("0.###", CultureInfo.InvariantCulture);

    static string RoundRectPath((float X, float Y, float W, float H, float R) r)
    {
        var (x, y, w, h, q) = r;
        if (q <= 0)
        {
            return $"M{F(x)},{F(y)}h{F(w)}v{F(h)}h{F(-w)}z";
        }

        return $"M{F(x + q)},{F(y)}h{F(w - 2 * q)}a{F(q)},{F(q)} 0 0 1 {F(q)},{F(q)}v{F(h - 2 * q)}"
            + $"a{F(q)},{F(q)} 0 0 1 {F(-q)},{F(q)}h{F(-(w - 2 * q))}a{F(q)},{F(q)} 0 0 1 {F(-q)},{F(-q)}"
            + $"v{F(-(h - 2 * q))}a{F(q)},{F(q)} 0 0 1 {F(q)},{F(-q)}z";
    }

    static string WavePath((float X, float Y, float R, float Width) w)
    {
        var a = WaveHalfAngle * MathF.PI / 180;
        var x = w.X + w.R * MathF.Cos(a);
        return $"M{F(x)},{F(w.Y - w.R * MathF.Sin(a))}A{F(w.R)},{F(w.R)} 0 0 1 {F(x)},{F(w.Y + w.R * MathF.Sin(a))}";
    }

    static string FillsPath => string.Concat(Fills.Select(RoundRectPath));

    public static string Svg(Color tile)
    {
        var sb = new StringBuilder();
        sb.AppendLine("<!-- tools/icon/MakeIcons.cs が書き出す確認用の絵。手で直さない。 -->");
        sb.AppendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"240\" height=\"240\">");
        sb.AppendLine($"  <path fill=\"{Hex(tile)}\" d=\"{RoundRectPath(Tile)}\"/>");
        sb.AppendLine($"  <path fill=\"#FFFFFF\" d=\"{FillsPath}\"/>");
        foreach (var wave in Waves)
        {
            sb.AppendLine($"  <path fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"{F(wave.Width)}\" stroke-linecap=\"round\" d=\"{WavePath(wave)}\"/>");
        }

        sb.AppendLine("</svg>");
        return sb.ToString();
    }

    /// <summary>白い絵だけ（地なし）のベクター。24 の格子を scale 倍して offset だけずらす。</summary>
    public static string AndroidVector(int canvas, double scale, double offset)
    {
        var s = scale.ToString("0.###", CultureInfo.InvariantCulture);
        var o = offset.ToString("0.###", CultureInfo.InvariantCulture);
        var sb = new StringBuilder();
        sb.AppendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.AppendLine("<!-- tools/icon/MakeIcons.cs が書き出す。手で直さない。 -->");
        sb.AppendLine("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"");
        sb.AppendLine($"    android:width=\"{canvas}dp\"");
        sb.AppendLine($"    android:height=\"{canvas}dp\"");
        sb.AppendLine($"    android:viewportWidth=\"{canvas}\"");
        sb.AppendLine($"    android:viewportHeight=\"{canvas}\">");
        sb.AppendLine($"    <group android:scaleX=\"{s}\" android:scaleY=\"{s}\" android:translateX=\"{o}\" android:translateY=\"{o}\">");
        sb.AppendLine("        <path");
        sb.AppendLine("            android:fillColor=\"#FFFFFF\"");
        sb.AppendLine($"            android:pathData=\"{FillsPath}\" />");
        foreach (var wave in Waves)
        {
            sb.AppendLine("        <path");
            sb.AppendLine("            android:strokeColor=\"#FFFFFF\"");
            sb.AppendLine($"            android:strokeWidth=\"{F(wave.Width)}\"");
            sb.AppendLine("            android:strokeLineCap=\"round\"");
            sb.AppendLine($"            android:pathData=\"{WavePath(wave)}\" />");
        }

        sb.AppendLine("    </group>");
        sb.AppendLine("</vector>");
        return sb.ToString();
    }
}
