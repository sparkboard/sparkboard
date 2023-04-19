module.exports = {
    content: ["./resources/public/**/*.js"],
    theme: {
        extend: {}
    },
    plugins: [
        require('@tailwindcss/typography'),
        require('@tailwindcss/forms'),
        require('@tailwindcss/line-clamp')
    ]
}