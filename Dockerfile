FROM lsiobase/ubuntu:jammy AS runtime-base

ENV DEBIAN_FRONTEND="noninteractive"

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    mkvtoolnix \
    libtesseract4 \
    libgomp1 && \
    apt-get -y clean && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/dotnet && \
    curl -o /tmp/dotnet-install.sh -L https://dot.net/v1/dotnet-install.sh && \
    chmod +x /tmp/dotnet-install.sh && \
    /tmp/dotnet-install.sh --version 8.0.24 --install-dir /app/dotnet --runtime dotnet

RUN mkdir -p /app/whisper && \
    curl -o /app/whisper/model.bin -L https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin

ENV DOTNET_ROOT=/app/dotnet
ENV WHISPER_MODEL=/app/whisper/model.bin

FROM mcr.microsoft.com/dotnet/sdk:8.0-jammy AS build
RUN apt-get update && apt-get install -y \
    libtiff5-dev \
    libtesseract-dev \
    tesseract-ocr-eng \
    build-essential \
    cmake \
    pkg-config \
    git \
    unzip

WORKDIR /source

RUN git clone --depth 1 https://github.com/ggerganov/whisper.cpp /tmp/whisper && \
    cmake -S /tmp/whisper -B /tmp/whisper/build \
        -DWHISPER_BUILD_TESTS=OFF \
        -DWHISPER_BUILD_EXAMPLES=ON \
        -DGGML_BLAS=OFF \
        -DBUILD_SHARED_LIBS=OFF && \
    cmake --build /tmp/whisper/build --config Release -j $(nproc) && \
    cp /tmp/whisper/build/bin/whisper-cli /usr/local/bin/whisper-cli

RUN git clone https://github.com/bubonic/VobSub2SRT && \
    cd VobSub2SRT && \
    ./configure && \
    make && \
    make install

RUN mkdir -p /tmp/pgstosrt && mkdir -p /app && \
    curl -o /tmp/pgstosrt/release.zip -L https://github.com/Tentacule/PgsToSrt/releases/download/v1.4.8/PgsToStr-1.4.8.zip && \
    unzip /tmp/pgstosrt/release.zip -d /tmp/pgstosrt && \
    mv /tmp/pgstosrt/ /app/pgstosrt && \
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
COPY --from=build /usr/local/bin/whisper-cli /usr/local/bin/whisper-cli
COPY --from=build /app ./
COPY wrapper.sh /app/wrapper.sh
ENTRYPOINT ["./wrapper.sh"]
