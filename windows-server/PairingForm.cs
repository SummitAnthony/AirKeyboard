namespace AirKeyboard
{
    /// <summary>
    /// The tray's pairing window: the PC name, a warning while no password is set, then a QR code
    /// and the address for each pairing address (the same list and payload as the console's
    /// pairing block).
    /// </summary>
    public class PairingForm : Form
    {
        private readonly List<Image> _images = new();
        private readonly FlowLayoutPanel _passwordWarning;

        public PairingForm(string serverName, bool showPasswordWarning, Action setPassword)
        {
            // Sizes below are 96-dpi pixels, scaled to the screen's DPI when layout resumes
            SuspendLayout();
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = "Pair with your device - AirKeyboard";
            Icon = AppIcon.Window;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            BackColor = Color.White;

            // GrowAndShrink, so the window shrinks when the password warning goes away
            var layout = new TableLayoutPanel
            {
                ColumnCount = 1, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, Padding = new Padding(16)
            };
            layout.Controls.Add(new Label
            {
                AutoSize = true,
                Font = new Font(Font.FontFamily, Font.Size * 1.3f, FontStyle.Bold),
                Text = serverName
            });

            _passwordWarning = new FlowLayoutPanel
            {
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                FlowDirection = FlowDirection.TopDown,
                BackColor = Color.FromArgb(255, 243, 205),
                Padding = new Padding(8),
                Margin = new Padding(3, 6, 3, 0),
                Visible = showPasswordWarning
            };
            _passwordWarning.Controls.Add(new Label
            {
                AutoSize = true,
                MaximumSize = new Size(304, 0),
                Text = "No password set. Anyone on your Wi-Fi with the app can control this PC and read its clipboard."
            });
            var setPasswordButton = new Button { AutoSize = true, Text = "Set password…", UseVisualStyleBackColor = true };
            setPasswordButton.Click += (_, _) => setPassword();
            _passwordWarning.Controls.Add(setPasswordButton);
            layout.Controls.Add(_passwordWarning);

            IReadOnlyList<PairingAddress> addresses = QRCodeGenerator.GetPairingAddresses();
            if (addresses.Count == 0)
            {
                layout.Controls.Add(new Label
                {
                    AutoSize = true,
                    MaximumSize = new Size(320, 0),
                    Text = "No network connection found. Connect this PC to Wi-Fi or Ethernet, then open this window again."
                });
            }
            else
            {
                layout.Controls.Add(new Label
                {
                    AutoSize = true,
                    MaximumSize = new Size(320, 0),
                    Margin = new Padding(3, 6, 3, 6),
                    Text = "In the AirKeyboard app, tap Scan QR code and point it at a code below, or type the address."
                });
                int pixelsPerModule = Math.Max(4, (int)Math.Round(6 * DeviceDpi / 96.0));
                foreach (PairingAddress pairing in addresses)
                {
                    string payload = QRCodeGenerator.PayloadFor(pairing.Address);
                    Image qr = QRCodeGenerator.GenerateBitmap(payload, pixelsPerModule);
                    _images.Add(qr);
                    layout.Controls.Add(new PictureBox { Image = qr, SizeMode = PictureBoxSizeMode.AutoSize, Anchor = AnchorStyles.None });
                    layout.Controls.Add(new Label
                    {
                        AutoSize = true,
                        Anchor = AnchorStyles.None,
                        Font = new Font(Font.FontFamily, Font.Size * 1.2f),
                        Margin = new Padding(3, 0, 3, 12),
                        Text = $"{payload}   ({pairing.InterfaceName})"
                    });
                }
            }
            Controls.Add(layout);
            ResumeLayout(false);
            PerformLayout();
        }

        public void ShowPasswordWarning(bool show) => _passwordWarning.Visible = show;

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                foreach (Image image in _images) image.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
