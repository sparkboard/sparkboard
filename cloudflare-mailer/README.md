# Transactional email using Cloudflare and Mailchannels
(at no cost)

References:
- https://github.com/maggie-j-liu/mail
- https://api.mailchannels.net/tx/v1/documentation

1. install wrangler 

```
yarn add wrangler
``` 

2. Set environment variable `CLOUDFLARE_API_TOKEN` (create a token using "Edit Cloudflare Workers" template) in order to deploy a worker to your account.

3. Create a secret 

```
openssl rand -hex 32
```

Copy it into your config under `:cloudflare.workers/secret-key`, and add it to your worker environment: 

```
npx wrangler secret put SECRET_KEY
```

4. Publish your worker:

```
npx wrangler publish
```

This will give you a URL like this: https://mailer.x.y.z

Put this in config under `:cloudflare.mailer/url`

The next steps are from https://github.com/maggie-j-liu/mail

5. Add a DNS TXT record for your domain: https://developers.cloudflare.com/pages/platform/functions/plugins/mailchannels/#spf-support-for-mailchannels

```
v=spf1 include:relay.mailchannels.net -all
```

(if you already have a spf1 record on your domain, add `include:relay.mailchannels.net` to it before `-all`)

6. Write a DKIM private key to private-key.txt: 

``` 
openssl genrsa 2048 | tee priv_key.pem | openssl rsa -outform der | openssl base64 -A > private-key.txt
```

Copy this into config under `:cloudflare.mailer/dkim-private-key`.

7. Add the public key to a TXT record in your DNS:

name: `mailchannels._domainkey`

value: run the following and then copy the contents of `record.txt`: 

``` 
echo -n "v=DKIM1;p=" > record.txt && openssl rsa -in priv_key.pem -pubout -outform der | openssl base64 -A >> record.txt
```

Now you can send email using the `send-email` function in `cloudflare.mailer` namespace.