using System.Drawing;
using System.Drawing.Drawing2D;
using System.Reflection;

namespace AirKeyboard
{
    /// <summary>
    /// The app icon: the embedded AirKeyboard.ico when the build had one, else a keyboard glyph
    /// drawn at runtime (white keys on a rounded blue square).
    /// </summary>
    public static class AppIcon
    {
        private static Icon? _window, _tray;

        /// <summary>For forms (all sizes of the .ico)</summary>
        public static Icon Window => _window ??= LoadEmbedded(null) ?? Draw(32);

        /// <summary>For the notification area: the small size, so it isn't scaled down blurry</summary>
        public static Icon Tray => _tray ??= LoadEmbedded(SystemInformation.SmallIconSize) ?? Draw(SystemInformation.SmallIconSize.Width);

        private static Icon? LoadEmbedded(Size? size)
        {
            using Stream? stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("AirKeyboard.ico");
            if (stream == null) return null;
            return size is Size s ? new Icon(stream, s) : new Icon(stream);
        }

        private static Icon Draw(int size)
        {
            using var bitmap = new Bitmap(size, size);
            using (Graphics g = Graphics.FromImage(bitmap))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                using (GraphicsPath body = RoundedRect(new RectangleF(1, 5, size - 2, size - 10), size / 6f))
                using (var blue = new SolidBrush(Color.FromArgb(0x1E, 0x6F, 0xE8)))
                {
                    g.FillPath(blue, body);
                }

                // Two rows of four keys, then a space bar
                float unit = size / 32f;
                using var white = new SolidBrush(Color.White);
                for (int row = 0; row < 2; row++)
                {
                    for (int col = 0; col < 4; col++)
                    {
                        g.FillRectangle(white, (5 + col * 6) * unit, (9 + row * 5) * unit, 4 * unit, 3 * unit);
                    }
                }
                g.FillRectangle(white, 9 * unit, 19 * unit, 14 * unit, 3 * unit);
            }
            // The icon handle lives as long as the app (one icon, never freed)
            return Icon.FromHandle(bitmap.GetHicon());
        }

        private static GraphicsPath RoundedRect(RectangleF r, float radius)
        {
            float d = radius * 2;
            var path = new GraphicsPath();
            path.AddArc(r.X, r.Y, d, d, 180, 90);
            path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }
    }
}
