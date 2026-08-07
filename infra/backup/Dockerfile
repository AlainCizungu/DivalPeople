# The backup service.
#
# Built on the same PostgreSQL version as the server it dumps. pg_dumpall from an older client
# against a newer server is refused outright, and from a newer client it can emit syntax the older
# server cannot restore — either way you find out when you restore, which is the wrong time.
FROM postgres:16.6-alpine

# age for encryption. Small, modern, and public-key: the host holds only the public half, so
# taking the server does not give you the backups.
RUN apk add --no-cache age tzdata

COPY backup.sh /usr/local/bin/backup.sh
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/backup.sh /usr/local/bin/entrypoint.sh

# Runs as root deliberately, and only this one. crond needs it, the container does nothing else,
# and it has no listening port. The trade is narrower than it looks.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
