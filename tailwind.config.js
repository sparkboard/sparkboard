/** @type {import('tailwindcss').Config} */
module.exports = {
    content: ["./src/**/*.{clj,cljs,cljc}"],
    theme: {
        extend: {
            colors: {
                border: "hsl(var(--border))",
                input: "hsl(var(--input))",
                ring: "hsl(var(--ring))",
                back: "hsl(var(--back))",
                "focus-accent": "hsl(var(--focus-accent))",
                badge: {
                    DEFAULT: "hsl(var(--badge))",
                    txt: "hsl(var(--badge-txt))",
                },
                txt: {
                    DEFAULT: "hsl(var(--txt))",
                    faded: "hsl(var(--txt) / 70%)"
                },
                primary: {
                    DEFAULT: "hsl(var(--primary))",
                    txt: "hsl(var(--primary-txt))",
                },
                secondary: {
                    DEFAULT: "hsl(var(--secondary))",
                    txt: "hsl(var(--secondary-txt))",
                },
                destructive: {
                    DEFAULT: "hsl(var(--destructive))",
                    txt: "hsl(var(--destructive-txt))",
                },
                muted: {
                    DEFAULT: "hsl(var(--muted))",
                    txt: "hsla(var(--muted-txt), 70%)",
                },
                accent: {
                    DEFAULT: "hsl(var(--accent))",
                    txt: "hsl(var(--accent-txt))",
                },
                popover: {
                    DEFAULT: "hsl(var(--popover))",
                    txt: "hsl(var(--popover-txt))",
                },
                card: {
                    DEFAULT: "hsl(var(--card))",
                    txt: "hsl(var(--card-txt))",
                },
            },
            borderRadius: {
                lg: `var(--radius)`,
                md: `calc(var(--radius) - 2px)`,
                sm: "calc(var(--radius) - 4px)",
            },
            keyframes: {
                "accordion-down": {
                    from: {height: 0},
                    to: {height: "var(--radix-accordion-content-height)"},
                },
                "accordion-up": {
                    from: {height: "var(--radix-accordion-content-height)"},
                    to: {height: 0},
                },
            },
            animation: {
                "accordion-down": "accordion-down 0.2s ease-out",
                "accordion-up": "accordion-up 0.2s ease-out",
            },
        },
    },
    plugins: [require("tailwindcss-animate"), require('@tailwindcss/typography'), require('@tailwindcss/forms'), require('@tailwindcss/line-clamp')],
}