/**
 * Sepolia 마스터 지갑의 stuck pending 트랜잭션을 취소하는 스크립트.
 * 각 stuck nonce에 자기 자신에게 0 ETH 전송 (높은 가스비) → 기존 tx 교체 → 확정
 */
const { ethers } = require('ethers');

const PRIVATE_KEY = '1533eb22331231555b642165b9c1ae2df192c7de0a48c782327660055e882e5e';
const RPC_URL = 'https://eth-sepolia.g.alchemy.com/v2/48rdcPmOO2K1i8uWVwVss';
const HIGH_GAS_PRICE = ethers.parseUnits('50', 'gwei'); // 50 Gwei (기존 10 Gwei의 5배)

async function clearStuckTransactions() {
  const provider = new ethers.JsonRpcProvider(RPC_URL);
  const wallet = new ethers.Wallet(PRIVATE_KEY, provider);

  const confirmedNonce = await provider.getTransactionCount(wallet.address, 'latest');
  const pendingNonce = await provider.getTransactionCount(wallet.address, 'pending');

  console.log(`지갑 주소: ${wallet.address}`);
  console.log(`확정된 nonce (latest): ${confirmedNonce}`);
  console.log(`대기 중 nonce (pending): ${pendingNonce}`);
  console.log(`stuck 트랜잭션 수: ${pendingNonce - confirmedNonce}`);

  if (pendingNonce <= confirmedNonce) {
    console.log('✅ stuck 트랜잭션 없음. 바로 테스트 가능합니다.');
    return;
  }

  const txHashes = [];
  for (let nonce = confirmedNonce; nonce < pendingNonce; nonce++) {
    console.log(`\nnonce ${nonce} 취소 트랜잭션 전송 중...`);
    try {
      const tx = await wallet.sendTransaction({
        to: wallet.address, // 자기 자신에게 0 ETH
        value: 0n,
        nonce: nonce,
        gasPrice: HIGH_GAS_PRICE,
        gasLimit: 21000n,
        chainId: 11155111n,
      });
      console.log(`  tx 전송 완료: ${tx.hash}`);
      txHashes.push(tx);
    } catch (e) {
      console.error(`  nonce ${nonce} 취소 실패: ${e.message}`);
    }
  }

  console.log('\n모든 취소 트랜잭션 채굴 대기 중...');
  for (const tx of txHashes) {
    const receipt = await tx.wait();
    console.log(`✅ nonce ${receipt.nonce ?? '?'} 취소 확정 - block: ${receipt.blockNumber}, txHash: ${tx.hash}`);
  }

  console.log('\n✅ 모든 stuck 트랜잭션 정리 완료! 이제 payment-service를 재시작하세요.');
}

clearStuckTransactions().catch(console.error);
