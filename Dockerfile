FROM lsiobase/ubuntu:focal AS runtime-base
RUN apt update && DEBIAN_FRONTEND="noninteractive" apt install -y ffmpeg mkvtoolnix libtesseract4

RUN mkdir -p /app/dotnet && \
    curl -o /tmp/dotnet-install.sh -L https://dot.net/v1/dotnet-install.sh && \
    chmod +x /tmp/dotnet-install.sh && \
    /tmp/dotnet-install.sh --version 6.0.5 --install-dir /app/dotnet --runtime dotnet

ENV DOTNET_ROOT=/app/dotnet
    
FROM mcr.microsoft.com/dotnet/sdk:6.0-focal as build
RUN apt update && DEBIAN_FRONTEND="noninteractive" apt install -y \
    libtiff5-dev \
    libtesseract-dev \
    tesseract-ocr-eng \
    build-essential \
    cmake \
    pkg-config \
    git \
    unzip

WORKDIR /source

RUN git clone https://github.com/bubonic/VobSub2SRT && \
    cd VobSub2SRT && \
    ./configure && \
    make && \
    make install

RUN mkdir -p /tmp/pgstosrt && mkdir -p /app && \
    curl -o /tmp/pgstosrt/release.zip -L https://github.com/Tentacule/PgsToSrt/releases/download/v1.4.2/PgsToSrt-1.4.2.zip && \
    unzip /tmp/pgstosrt/release.zip -d /tmp/pgstosrt && \
    mv /tmp/pgstosrt/net6 /app/pgstosrt && \
    mkdir -p /app/pgstosrt/tessdata && \
    curl -o /app/pgstosrt/tessdata/eng.traineddata -L https://github.com/tesseract-ocr/tessdata_best/raw/main/eng.traineddata

COPY *.sln .
COPY *.csproj .
RUN dotnet restore -r linux-x64 .

COPY . ./
RUN dotnet publish TvRename.csproj -c release -o /app -r linux-x64 --self-contained false --no-restore -p:DebugType=Embedded -p:InformationalVersion=${INFO_VERSION}

FROM runtime-base
ENV PATH="/app/dotnet:${PATH}"
ENV CACHE_FOLDER="/cache"
WORKDIR /app
COPY --from=build /usr/local/bin/vobsub2srt /usr/local/bin/vobsub2srt
COPY --from=build /app ./
COPY wrapper.sh /app/wrapper.sh
ENTRYPOINT ["./wrapper.sh"]
