
export default {

    async fetch(request, env) {

        // protect this service using a secret key
        if (env.SECRET_KEY !== request.headers.get('X-Secret-Key')) {
            return new Response('Invalid secret key', {status: 403});
        }

        return fetch(
            new Request('https://api.mailchannels.net/tx/v1/send', {
                method: 'POST',
                headers: {
                    'content-type': 'application/json'
                },
                // pass through the request body directly,
                // logic/structure is handled elsewhere
                body: await request.text()
            })
        )
    },
}