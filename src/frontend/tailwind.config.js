/** @type {import('tailwindcss').Config} */
export default {
    content: [
        './index.html',
        './src/**/*.{vue,js,ts,jsx,tsx}',
        '../backend/extensions/*/src/frontend/src/**/*.{vue,js,ts}'
    ],
    theme: {
        screens: {
            'sm': '640px',
            'md': '768px',
            'lg': '1024px',  // Tablets in landscape and small desktops
            'xl': '1280px',
            '2xl': '1536px',
        },
        extend: {
            colors: {
                blue: {
                    50: 'var(--color-blue-50)',
                    100: 'var(--color-blue-100)',
                    200: 'var(--color-blue-200)',
                    300: 'var(--color-blue-300)',
                    400: 'var(--color-blue-400)',
                    500: 'var(--color-blue-500)',
                    600: 'var(--color-blue-600)',
                    700: 'var(--color-blue-700)',
                    800: 'var(--color-blue-800)',
                    900: 'var(--color-blue-900)',
                },
                red: {
                    50: 'var(--color-red-50)',
                    100: 'var(--color-red-100)',
                    200: 'var(--color-red-200)',
                    300: 'var(--color-red-300)',
                    400: 'var(--color-red-400)',
                    500: 'var(--color-red-500)',
                    600: 'var(--color-red-600)',
                    700: 'var(--color-red-700)',
                    800: 'var(--color-red-800)',
                    900: 'var(--color-red-900)',
                },
                green: {
                    50: 'var(--color-green-50)',
                    100: 'var(--color-green-100)',
                    200: 'var(--color-green-200)',
                    300: 'var(--color-green-300)',
                    400: 'var(--color-green-400)',
                    500: 'var(--color-green-500)',
                    600: 'var(--color-green-600)',
                    700: 'var(--color-green-700)',
                    800: 'var(--color-green-800)',
                    900: 'var(--color-green-900)',
                },
                yellow: {
                    50: 'var(--color-yellow-50)',
                    100: 'var(--color-yellow-100)',
                    200: 'var(--color-yellow-200)',
                    300: 'var(--color-yellow-300)',
                    400: 'var(--color-yellow-400)',
                    500: 'var(--color-yellow-500)',
                    600: 'var(--color-yellow-600)',
                    700: 'var(--color-yellow-700)',
                    800: 'var(--color-yellow-800)',
                    900: 'var(--color-yellow-900)',
                },
                purple: {
                    50: 'var(--color-purple-50)',
                    100: 'var(--color-purple-100)',
                    200: 'var(--color-purple-200)',
                    300: 'var(--color-purple-300)',
                    400: 'var(--color-purple-400)',
                    500: 'var(--color-purple-500)',
                    600: 'var(--color-purple-600)',
                    700: 'var(--color-purple-700)',
                    800: 'var(--color-purple-800)',
                    900: 'var(--color-purple-900)',
                },
            },
        },
    },
    plugins: [
        require('@tailwindcss/typography'),
    ],
}

