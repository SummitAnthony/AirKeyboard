namespace AirKeyboard
{
    /// <summary>
    /// Asks for a new password twice (the tray's version of --set-password, same rules). On OK,
    /// Config holds it as a key, ready to Save().
    /// </summary>
    public class PasswordDialog : Form
    {
        private readonly TextBox _first = new() { UseSystemPasswordChar = true, Width = 260 };
        private readonly TextBox _second = new() { UseSystemPasswordChar = true, Width = 260 };
        private readonly Label _error = new() { AutoSize = true, ForeColor = Color.Firebrick };

        public AuthConfig Config { get; } = new();

        public PasswordDialog(bool change)
        {
            // Sizes below are 96-dpi pixels, scaled to the screen's DPI when layout resumes
            SuspendLayout();
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = change ? "Change password - AirKeyboard" : "Set password - AirKeyboard";
            Icon = AppIcon.Window;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            ShowInTaskbar = true;

            var ok = new Button { Text = "OK", AutoSize = true };
            var cancel = new Button { Text = "Cancel", AutoSize = true, DialogResult = DialogResult.Cancel };
            ok.Click += (_, _) => Accept();
            AcceptButton = ok;
            CancelButton = cancel;

            var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Dock = DockStyle.Fill };
            buttons.Controls.Add(cancel);
            buttons.Controls.Add(ok);

            var layout = new TableLayoutPanel { ColumnCount = 1, AutoSize = true, Padding = new Padding(12) };
            layout.Controls.Add(new Label
            {
                AutoSize = true,
                MaximumSize = new Size(300, 0),
                Text = $"Choose a password for this PC ({AuthConfig.MinPasswordChars} to {AuthConfig.MaxPasswordChars} " +
                       "characters; 12 or more is better). The app asks for it once when it connects."
            });
            layout.Controls.Add(new Label { AutoSize = true, Text = "New password:", Margin = new Padding(3, 10, 3, 0) });
            layout.Controls.Add(_first);
            layout.Controls.Add(new Label { AutoSize = true, Text = "Type it again:", Margin = new Padding(3, 6, 3, 0) });
            layout.Controls.Add(_second);
            layout.Controls.Add(_error);
            layout.Controls.Add(buttons);
            Controls.Add(layout);
            ResumeLayout(false);
            PerformLayout();
        }

        private void Accept()
        {
            if (_first.Text != _second.Text)
            {
                _error.Text = "The passwords don't match.";
                return;
            }
            try
            {
                Config.SetPassword(_first.Text);
            }
            catch (ArgumentException ex)
            {
                _error.Text = ex.Message;
                return;
            }
            DialogResult = DialogResult.OK;
        }
    }
}
