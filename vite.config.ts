import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {defineConfig, Plugin} from 'vite';
import {VitePWA} from 'vite-plugin-pwa';

const ttsDevPlugin = (): Plugin => ({
  name: 'tts-api-plugin',
  configureServer(server) {
    server.middlewares.use('/api/tts', async (req, res) => {
      try {
        const urlObj = new URL(req.url || '', 'http://localhost:3000');
        const rawText = urlObj.searchParams.get('text') || '';
        const trimmed = rawText.trim();
        if (!trimmed) {
          res.statusCode = 400;
          res.end(JSON.stringify({ error: 'Text parameter required' }));
          return;
        }
        let lang = urlObj.searchParams.get('lang') || '';
        if (!lang || (lang !== 'bn' && lang !== 'en')) {
          lang = /[\u0980-\u09FF]/.test(trimmed) ? 'bn' : 'en';
        }
        const encoded = encodeURIComponent(trimmed.slice(0, 180));
        const googleUrl = `https://translate.google.com/translate_tts?ie=UTF-8&q=${encoded}&tl=${lang}&client=tw-ob`;
        const response = await fetch(googleUrl, {
          headers: {
            'User-Agent':
              'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
            Accept: 'audio/mpeg, audio/*;q=0.9, */*;q=0.8',
          },
        });
        if (!response.ok) {
          res.statusCode = 502;
          res.end(JSON.stringify({ error: 'TTS upstream error' }));
          return;
        }
        const ab = await response.arrayBuffer();
        const buf = Buffer.from(ab);
        res.setHeader('Content-Type', 'audio/mpeg');
        res.setHeader('Content-Length', buf.length);
        res.setHeader('Cache-Control', 'public, max-age=86400');
        res.end(buf);
      } catch (err: unknown) {
        res.statusCode = 500;
        const msg = err instanceof Error ? err.message : String(err);
        res.end(JSON.stringify({ error: msg }));
      }
    });
  },
});

export default defineConfig(() => {
  return {
    plugins: [
      react(),
      tailwindcss(),
      ttsDevPlugin(),
      VitePWA({
        registerType: 'autoUpdate',
        includeAssets: [
          'favicon.ico',
          'favicon.png',
          'apple-touch-icon.png',
          'icon.svg',
          'icon-maskable.svg',
          'pwa-192x192.png',
          'pwa-512x512.png',
          'pwa-maskable-512x512.png',
          'manifest.json',
        ],
        manifest: {
          id: '/',
          name: 'চার্জিং সহকারী - Bengali Charging Assistant',
          short_name: 'চার্জ সহকারী',
          description: 'একটি নির্ভরযোগ্য, অফলাইন-বান্ধব ও প্রফেশনাল বাংলা চার্জিং সহকারী এবং ব্যাটারি মনিটর অ্যাপ্লিকেশন।',
          lang: 'bn',
          theme_color: '#10b981',
          background_color: '#09090b',
          display: 'standalone',
          display_override: ['window-controls-overlay', 'standalone'],
          orientation: 'portrait',
          start_url: '/',
          scope: '/',
          categories: ['utilities', 'productivity'],
          icons: [
            {
              src: '/pwa-192x192.png',
              sizes: '192x192',
              type: 'image/png',
              purpose: 'any',
            },
            {
              src: '/pwa-512x512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'any',
            },
            {
              src: '/pwa-maskable-512x512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'maskable',
            },
            {
              src: '/icon.svg',
              sizes: '512x512',
              type: 'image/svg+xml',
              purpose: 'any',
            },
          ],
        },
        workbox: {
          globPatterns: ['**/*.{js,css,html,ico,png,svg,woff,woff2,json,mp3,wav}'],
          runtimeCaching: [
            {
              urlPattern: /\/audio\/.*\.(?:mp3|wav)$/i,
              handler: 'CacheFirst',
              options: {
                cacheName: 'bengali-audio-cache',
                expiration: {
                  maxEntries: 150,
                  maxAgeSeconds: 60 * 60 * 24 * 365, // 1 year
                },
                cacheableResponse: {
                  statuses: [0, 200],
                },
              },
            },
            {
              urlPattern: /^https:\/\/fonts\.googleapis\.com\/.*/i,
              handler: 'CacheFirst',
              options: {
                cacheName: 'google-fonts-cache',
                expiration: {
                  maxEntries: 10,
                  maxAgeSeconds: 60 * 60 * 24 * 365,
                },
                cacheableResponse: {
                  statuses: [0, 200],
                },
              },
            },
            {
              urlPattern: /^https:\/\/fonts\.gstatic\.com\/.*/i,
              handler: 'CacheFirst',
              options: {
                cacheName: 'gstatic-fonts-cache',
                expiration: {
                  maxEntries: 10,
                  maxAgeSeconds: 60 * 60 * 24 * 365,
                },
                cacheableResponse: {
                  statuses: [0, 200],
                },
              },
            },
          ],
        },
        devOptions: {
          enabled: true,
          type: 'module',
        },
      }),
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
      },
    },
    server: {
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modifyâfile watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // Disable file watching when DISABLE_HMR is true to save CPU during agent edits.
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
    },
  };
});
