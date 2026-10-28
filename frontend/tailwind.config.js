/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  darkMode: ['selector', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        cyber: {
          royal: '#2563EB',
          electric: '#3B82F6',
          cyan: '#06B6D4',
          incognito: '#6366F1',
          darkBg: '#0B0F19',
          darkCard: '#111827',
          lightBg: '#F8FAFC',
          lightCard: '#FFFFFF',
          incognitoBg: '#07090E',
        },
        page: 'var(--bg-page)',
        card: 'var(--bg-card)',
        elevated: 'var(--bg-elevated)',
        primary: {
          DEFAULT: 'var(--color-primary)',
          light: 'var(--color-primary-light)',
        },
        accent: 'var(--color-accent)',
        main: 'var(--text-primary)',
        sub: 'var(--text-secondary)',
        muted: 'var(--text-muted)',
        subtle: 'var(--border-subtle)',
      },
      boxShadow: {
        'cyber-glow': '0 0 24px -4px rgba(59, 130, 246, 0.45)',
        'cyber-cyan': '0 0 20px -4px rgba(6, 182, 212, 0.4)',
        'incognito-glow': '0 0 24px -4px rgba(99, 102, 241, 0.45)',
        'glass-inner': 'inset 0 1px 0 0 rgba(255, 255, 255, 0.12)',
      },
      backdropBlur: {
        xs: '4px',
        md: '12px',
        xl: '16px',
      },
      keyframes: {
        slideUp: {
          '0%': { opacity: '0', transform: 'translateY(18px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        pulseGlow: {
          '0%, 100%': { opacity: '0.65' },
          '50%': { opacity: '1' },
        },
      },
      animation: {
        'slide-up': 'slideUp 600ms cubic-bezier(0.22, 1, 0.36, 1) forwards',
        'pulse-glow': 'pulseGlow 2.4s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
