# The sim host. Build context: server/host/, with dist/ staged from Gradle's installDist on the Mac by
# server/deploy/stage-host.sh, so the box never runs Gradle and the Mac's CPU type doesn't matter.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY dist/ .
RUN useradd --system --uid 10001 lilguy && mkdir /data && chown lilguy /data
USER lilguy
VOLUME /data
EXPOSE 8790
CMD ["bin/host"]
